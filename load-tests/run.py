"""Open-loop k6 runs; preserves failed threshold exits and original raw summaries."""
import argparse,json,os,re,subprocess,threading,time,urllib.request
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--state',default='/tmp/search-load-env.json');p.add_argument('--output',required=True);p.add_argument('--duration',default='10s');p.add_argument('--roles',nargs='+',default=['local','coordinator']);p.add_argument('--rps',type=int,default=500);p.add_argument('--high-rps',type=int,default=1000);p.add_argument('--updates',action='store_true');p.add_argument('--update-duration',type=int,default=15);p.add_argument('--workloads',nargs='+',choices=['cache-hit','cache-miss','short-prefix','fuzzy'],default=['cache-hit','cache-miss','short-prefix','fuzzy']);a=p.parse_args()
root=Path(__file__).resolve().parents[1];out=Path(a.output).resolve();out.mkdir(parents=True,exist_ok=True)
state=json.loads(Path(a.state).read_text());(out/'environment.json').write_text(json.dumps(state,indent=2)+'\n')
def metrics(url):
 try:
  with urllib.request.urlopen(url+'/actuator/prometheus',timeout=3) as r:text=r.read().decode()
  result={}
  for line in text.splitlines():
   if line.startswith('#'):continue
   name,_,value=line.rpartition(' ')
   try:result[name]=float(value)
   except ValueError:pass
  return result
 except Exception:return {}
def cache_delta(before,after):
 hit=sum(v-before.get(k,0) for k,v in after.items() if k.startswith('autocomplete_cache_total{') and 'result="hit"' in k)
 miss=sum(v-before.get(k,0) for k,v in after.items() if k.startswith('autocomplete_cache_total{') and 'result="miss"' in k)
 return {'hits':hit,'misses':miss,'hit_ratio':hit/(hit+miss) if hit+miss else None}
def resources(stop,samples):
 pids=','.join(str(v['pid']) for v in state['roles'].values())
 while not stop.is_set():
  result=subprocess.run(['ps','-o','pid=,rss=,%cpu=','-p',pids],capture_output=True,text=True)
  for line in result.stdout.splitlines():
   try:pid,rss,cpu=line.split();samples.append({'time':time.time(),'pid':int(pid),'rss_kib':int(rss),'lifetime_cpu_percent':float(cpu)})
   except ValueError:pass
  stop.wait(1)
runs=[(role,work,a.rps,a.duration,False) for role in a.roles for work in a.workloads]
runs += [(role,'cache-hit',a.high_rps,a.duration,False) for role in a.roles]
if a.updates:runs.append(('coordinator','mixed',a.rps,f'{a.update_duration}s',True))
rows=[]
for number,(role,workload,rate,duration,updates) in enumerate(runs):
 name=f'{number:02d}-{role}-{workload}-{rate}'+('-updates' if updates else '')
 url=state['roles'][role]['url'];before=metrics(url)
 for prefix in ['catalog%20phone%200','c']:
  try:urllib.request.urlopen(url+f'/api/v1/autocomplete?prefix={prefix}&category=load',timeout=3).close()
  except Exception:pass
 updater=None
 if updates:updater=subprocess.Popen(['python3',str(root/'load-tests/updates.py'),'--duration',str(a.update_duration),'--probe-url',url,'--output',str(out/(name+'-updates.json'))],stdout=(out/(name+'-updates.log')).open('w'))
 stop=threading.Event();samples=[];thread=threading.Thread(target=resources,args=(stop,samples));thread.start()
 env={**os.environ,'BASE_URL':url,'WORKLOAD':workload,'RPS':str(rate),'DURATION':duration,'OFFSET':str(number*20000),'K6_NO_USAGE_REPORT':'true'}
 started_at=time.time();start=time.monotonic()
 with (out/(name+'.log')).open('w') as log:
  result=subprocess.run(['k6','run','--no-color','--summary-export',str(out/(name+'.json')),str(root/'load-tests/autocomplete.js')],env=env,stdout=log,stderr=subprocess.STDOUT)
 elapsed=time.monotonic()-start;stop.set();thread.join()
 if updater:updater.wait(timeout=30)
 after=metrics(url);(out/(name+'-resources.json')).write_text(json.dumps(samples,indent=2)+'\n')
 (out/(name+'-server-metrics.json')).write_text(json.dumps({'before':before,'after':after},indent=2)+'\n')
 row={'run':name,'role':role,'workload':workload,'offered_rps':rate,'duration':duration,'wall_seconds':elapsed,'started_at_unix':started_at,'finished_at_unix':time.time(),'k6_exit':result.returncode,'cache':cache_delta(before,after)}
 summary=out/(name+'.json')
 if summary.exists():row['metrics']=json.loads(summary.read_text()).get('metrics',{})
 rows.append(row);(out/'runs.json').write_text(json.dumps(rows,indent=2)+'\n');print(json.dumps({k:v for k,v in row.items() if k!='metrics'}),flush=True)
print('Saved raw summaries and run metadata to '+str(out),flush=True)
