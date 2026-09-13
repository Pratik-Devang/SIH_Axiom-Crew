import json,sys
from pathlib import Path
import numpy as np, torch
from torch.utils.data import DataLoader
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from src.ml.preprocessing import load_config,load_split_trips,apply_normalization,fit_normalization
from src.ml.dataset import SpeedWindowDataset
from src.ml.tcn import build_model
for rid in sys.argv[1:]:
 ck=torch.load(ROOT/'artifacts/runs'/rid/'checkpoint.pt',map_location='cpu',weights_only=False); c=ck['config']; s=load_split_trips(c)['validation']; st=ck['normalization']; frames=[apply_normalization(f,st) for f in s]; ds=SpeedWindowDataset(frames,50,stride=10); dl=DataLoader(ds,batch_size=128);m=build_model(c);m.load_state_dict(ck['model_state_dict']);m.eval();P=[];Y=[]; trip=[]
 with torch.no_grad():
  for i,(x,y) in enumerate(dl): P.append(m(x).numpy());Y.append(y.numpy())
 p=np.concatenate(P);y=np.concatenate(Y);e=p-y; print(rid,'overall',{'mae':float(np.mean(abs(e))*3.6),'rmse':float(np.sqrt(np.mean(e*e))*3.6),'bias':float(np.mean(e)*3.6),'r2':float(1-np.sum(e*e)/np.sum((y-y.mean())**2))})
 for lo,hi in [(0,5),(5,20),(20,40),(40,60),(60,1e9)]:
  q=(y*3.6>=lo)&(y*3.6<hi); print('bin',lo,hi,int(q.sum()),float(np.mean(abs(e[q]))*3.6) if q.any() else None)
 q=y*3.6<0.5; print('stationary',int(q.sum()),float(np.mean(abs(e[q]))*3.6) if q.any() else None)
