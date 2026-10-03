"""Validation-only low-cost TCN screening; frozen test trips are never loaded."""
from __future__ import annotations
import argparse, hashlib, json, time, sys
from pathlib import Path
import numpy as np, torch
from torch.utils.data import DataLoader, WeightedRandomSampler
ROOT=Path(__file__).resolve().parents[1]; sys.path.insert(0,str(ROOT))
from src.ml.dataset import SpeedWindowDataset
from src.ml.preprocessing import apply_normalization, fit_normalization, load_config, load_split_trips, save_json, set_seed
from src.ml.tcn import build_model

def metrics(p,y):
 e=p-y; return {'mae_mps':float(np.mean(abs(e))),'rmse_mps':float(np.sqrt(np.mean(e*e))),'bias_mps':float(np.mean(e))}

def main():
 ap=argparse.ArgumentParser(); ap.add_argument('--mode',choices=['balanced','low_speed','huber'],required=True); ap.add_argument('--epochs',type=int,default=5); ap.add_argument('--patience',type=int,default=2); ap.add_argument('--device',default='cuda'); a=ap.parse_args()
 if a.device=='cuda' and not torch.cuda.is_available(): raise RuntimeError('CUDA requested but unavailable')
 c=load_config(); set_seed(c['training']['seed']); device=torch.device(a.device)
 splits=load_split_trips(c); train_raw=splits['train']; val_raw=splits['validation']; stats=fit_normalization(train_raw)
 train=[apply_normalization(x,stats) for x in train_raw]; val=[apply_normalization(x,stats) for x in val_raw]
 tr=SpeedWindowDataset(train,c['data']['window_samples'],c['data']['stride']); va=SpeedWindowDataset(val,c['data']['window_samples'],stride=10)
 weights=None
 if a.mode in ('balanced','low_speed'):
  vals=np.array([tr.trips_targets[t][s+tr.window_samples-1] for t,s in tr.index_map],dtype=np.float32)
  trip_counts=np.bincount([t for t,s in tr.index_map],minlength=len(tr.trips_targets)); w=1/np.maximum(trip_counts,1)
  weights=np.array([w[t] for t,s in tr.index_map],dtype=np.float64)
  if a.mode=='low_speed': weights*=np.where(vals<0.5,8.0,np.where(vals<5/3.6,4.0,np.where(vals<20/3.6,2.0,1.0)))
  sampler=WeightedRandomSampler(torch.as_tensor(weights,dtype=torch.double),len(weights),replacement=True); loader=DataLoader(tr,batch_size=c['training']['batch_size'],sampler=sampler,pin_memory=True)
 else: loader=DataLoader(tr,batch_size=c['training']['batch_size'],shuffle=True,pin_memory=True)
 vloader=DataLoader(va,batch_size=c['training']['batch_size'],shuffle=False,pin_memory=True)
 payload=json.dumps({'mode':a.mode,'config':c,'seed':c['training']['seed']},sort_keys=True).encode(); rid=hashlib.sha256(payload).hexdigest()[:16]; out=ROOT/'artifacts'/'runs'/rid; out.mkdir(parents=True,exist_ok=True)
 save_json(c,out/'config.json'); save_json({'columns':stats['columns'],'mean':stats['mean'],'std':stats['std']},out/'normalization.json'); save_json({'train':[x for x in c['data']['split_manifest'] and ['S1','S2','S3a','S3b','S3c','S4','Vw1','Vw2','Vw3','Vw4','Vw5','Vw6','Vw7','Vw8','Vw9','Vw10','Vw11','Vw12','Vw13','Vw14a','Vw14b','Vw14c','Vw15','Vw16a','Vw16b','Vw17']],'validation':['M','Vfa01','Vfa02']},out/'split_manifest.json')
 m=build_model(c).to(device); opt=torch.optim.Adam(m.parameters(),lr=c['training']['learning_rate'],weight_decay=c['training']['weight_decay']); best=float('inf'); no=0; hist=[]; start=time.perf_counter()
 for ep in range(1,a.epochs+1):
  m.train(); tl=[]
  for x,y in loader:
   x=x.to(device,non_blocking=True); y=y.to(device,non_blocking=True); opt.zero_grad(set_to_none=True); pred=m(x); loss=torch.nn.functional.smooth_l1_loss(pred,y) if a.mode=='huber' else torch.mean((pred-y)**2); loss.backward(); opt.step(); tl.append(loss.item())
  m.eval(); pp=[]; yy=[]
  with torch.no_grad():
   for x,y in vloader: pp.append(m(x.to(device,non_blocking=True)).cpu().numpy()); yy.append(y.numpy())
  p=np.concatenate(pp); y=np.concatenate(yy); z=metrics(p,y); z.update({'epoch':ep,'train_loss':float(np.mean(tl)),'validation_loss':float(np.mean((p-y)**2))}); hist.append(z); print({'run':rid,**z},flush=True)
  if z['validation_loss']<best: best=z['validation_loss'];no=0; torch.save({'model_state_dict':m.state_dict(),'config':c,'normalization':stats,'run_id':rid,'mode':a.mode,'split_trips':{'validation':['M','Vfa01','Vfa02']}},out/'checkpoint.pt')
  else: no+=1
  if no>=a.patience: break
 save_json({'run_id':rid,'mode':a.mode,'history':hist,'elapsed_seconds':time.perf_counter()-start},out/'training_history.json'); save_json({'run_id':rid,'mode':a.mode,'best_validation_loss':best,'epochs_completed':len(hist)},out/'model_info.json'); print({'run_id':rid,'path':str(out),'best_validation_loss':best,'epochs':len(hist)},flush=True)
if __name__=='__main__': main()
