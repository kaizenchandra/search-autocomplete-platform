"""Acknowledge each committed revision; do not count enqueued SQL as successful updates."""
import argparse,json,subprocess,time,statistics,math,urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--duration',type=float,default=15);p.add_argument('--rps',type=float,default=20);p.add_argument('--output',required=True);p.add_argument('--probe-url');a=p.parse_args()
root=Path(__file__).resolve().parents[1]
proc=subprocess.Popen(['docker','compose','-f',str(root/'compose.yml'),'exec','-T','postgres','psql','-qAt','-v','ON_ERROR_STOP=1','-U','autocomplete','-d','autocomplete'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
def probe(index,popularity,committed):
 while time.monotonic()-committed<3:
  try:
   with urllib.request.urlopen(a.probe_url+f'/api/v1/autocomplete?category=load&prefix=catalog%20phone%20{index:05d}&limit=1',timeout=.5) as response:body=json.load(response)
   if body['suggestions'] and body['suggestions'][0]['id']==f'loadbench-{index}' and body['suggestions'][0]['score']>=math.log1p(popularity)-1e-10:return (time.monotonic()-committed)*1000
  except Exception:pass
  time.sleep(.05)
 return None
pool=ThreadPoolExecutor(max_workers=3);probes=[];last_probe=0
start=time.monotonic();latencies=[];count=0
try:
 while time.monotonic()-start<a.duration:
  step=time.monotonic();index=count%128;popularity=100000+count
  sql=f"begin;select version from corpus_revision where id=1 for update;update suggestions set popularity={popularity} where tenant='public' and id='loadbench-{index}';update corpus_revision set version=version+1 where id=1;insert into index_update_events(revision,kind,tenant) select version,'COMMIT','public' from corpus_revision where id=1;commit;select 'ACK';\n"
  proc.stdin.write(sql);proc.stdin.flush()
  while True:
   line=proc.stdout.readline()
   if not line:raise RuntimeError('Update process terminated')
   if line.strip()=='ACK':break
  if a.probe_url and time.monotonic()-last_probe>=1 and len(probes)<300:
   last_probe=time.monotonic();probes.append(pool.submit(probe,index,popularity,last_probe))
  count+=1;latencies.append((time.monotonic()-step)*1000)
  delay=1/a.rps-(time.monotonic()-step)
  if delay>0:time.sleep(delay)
finally:
 proc.stdin.close();proc.wait(timeout=15)
 elapsed=time.monotonic()-start
 ordered=sorted(latencies)
 result={'requested_update_rps':a.rps,'committed_updates':count,'elapsed_seconds':elapsed,'achieved_update_rps':count/elapsed,'commit_p50_ms':statistics.median(ordered) if ordered else None,'commit_p99_ms':ordered[min(len(ordered)-1,int(len(ordered)*.99))] if ordered else None}
 visibility=[f.result() for f in probes];pool.shutdown();visible=sorted(v for v in visibility if v is not None)
 result.update({'visibility_samples':len(visibility),'not_visible_within_3s':sum(v is None for v in visibility),'visibility_completed_p50_ms':statistics.median(visible) if visible else None,'visibility_completed_max_ms':max(visible) if visible else None})
 Path(a.output).write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
