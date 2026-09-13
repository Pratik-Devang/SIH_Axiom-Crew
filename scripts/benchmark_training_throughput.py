from __future__ import annotations
import time, sys
from pathlib import Path
import torch
from torch.utils.data import DataLoader
ROOT=Path(__file__).resolve().parents[1]; sys.path.insert(0,str(ROOT))
from src.ml.dataset import SpeedWindowDataset
from src.ml.preprocessing import apply_normalization, fit_normalization, load_config, load_split_trips
from src.ml.tcn import build_model

def setup(batch, workers=0):
    c=load_config(); s=load_split_trips(c); st=fit_normalization(s['train'])
    ds=SpeedWindowDataset([apply_normalization(f,st) for f in s['train']],c['data']['window_samples'],c['data']['stride'])
    dl=DataLoader(ds,batch_size=batch,shuffle=True,num_workers=workers,pin_memory=True,persistent_workers=workers>0)
    m=build_model(c).cuda().train(); o=torch.optim.Adam(m.parameters(),lr=c['training']['learning_rate'],weight_decay=c['training']['weight_decay'])
    return c,ds,dl,m,o

def run(batch, workers=0, amp=False, count=500):
    c,ds,dl,m,o=setup(batch,workers); it=iter(dl); next(it)
    torch.cuda.synchronize(); start=time.perf_counter(); load=[]; comp=[]
    scaler=torch.amp.GradScaler('cuda',enabled=amp)
    for _ in range(count):
        t=time.perf_counter(); x,y=next(it); load.append(time.perf_counter()-t)
        x=x.cuda(non_blocking=True); y=y.cuda(non_blocking=True); torch.cuda.synchronize(); t=time.perf_counter()
        with torch.autocast('cuda',dtype=torch.float16,enabled=amp): loss=((m(x)-y)**2).mean()
        scaler.scale(loss).backward(); scaler.step(o); scaler.update(); o.zero_grad(set_to_none=True); torch.cuda.synchronize(); comp.append(time.perf_counter()-t)
    elapsed=time.perf_counter()-start
    return {'batch':batch,'workers':workers,'amp':amp,'windows':len(ds),'batches_epoch':(len(ds)+batch-1)//batch,'batches':count,'seconds':elapsed,'batches_sec':count/elapsed,'samples_sec':count*batch/elapsed,'avg_load_ms':sum(load)/len(load)*1000,'avg_compute_ms':sum(comp)/len(comp)*1000,'vram_mb':torch.cuda.max_memory_allocated()/1024**2}

def main():
    c,ds,dl,m,o=setup(c:=load_config()['training']['batch_size'] if False else 128,0)
    print({'windows':len(ds),'batch_size':128,'batches_epoch':len(dl),'expected_epoch_seconds_at_profile':len(dl)/(5322.8/128)})
    for b in (32,64,128,256,512):
        try: print(run(b,count=500))
        except RuntimeError as e:
            if 'out of memory' in str(e).lower(): print({'batch':b,'oom':True}); torch.cuda.empty_cache(); break
            raise
    for w in (0,1,2):
        try: print(run(128,workers=w,count=500))
        except Exception as e: print({'workers':w,'error':repr(e)})
    print('FP32',run(128,amp=False,count=500)); print('AMP',run(128,amp=True,count=500))
    c,ds,dl,m,o=setup(128,0); torch.cuda.synchronize(); t=time.perf_counter()
    for x,y in dl:
        x=x.cuda(non_blocking=True); y=y.cuda(non_blocking=True); loss=((m(x)-y)**2).mean(); loss.backward(); o.step(); o.zero_grad(set_to_none=True)
    torch.cuda.synchronize(); print({'full_epoch_seconds':time.perf_counter()-t,'windows':len(ds),'batches':len(dl),'vram_mb':torch.cuda.max_memory_allocated()/1024**2})
if __name__=='__main__': main()
