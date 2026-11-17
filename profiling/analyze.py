import argparse,json,collections,datetime,re
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('events');p.add_argument('--runs',required=True);p.add_argument('--output',required=True);a=p.parse_args()
events=json.loads(Path(a.events).read_text())['recording']['events'];rows=json.loads(Path(a.runs).read_text());result={}
def duration(s):return sum(float(n)*{'H':3600000,'M':60000,'S':1000}[unit] for n,unit in re.findall(r'([0-9.]+)([HMS])',s))
import bisect
starts=[row['started_at_unix'] for row in rows]
buckets=[[] for _ in rows]
for event in events:
 t=datetime.datetime.fromisoformat(event['values']['startTime']).timestamp();i=bisect.bisect_right(starts,t)-1
 if i>=0 and t<=rows[i]['finished_at_unix']:buckets[i].append(event)
for index,row in enumerate(rows):
 stages=collections.defaultdict(list);cpu=collections.Counter();monitors=collections.defaultdict(list);parks=collections.Counter();samples=0
 for e in buckets[index]:
  v=e['values'];frames=(v.get('stackTrace') or {}).get('frames',[]);names=[f['method']['type']['name']+'.'+f['method']['name'] for f in frames]
  if e['type']=='autocomplete.Stage':stages[v['stage']].append(duration(v['duration']))
  if e['type']=='jdk.ExecutionSample':
   samples+=1
   for n in set(names):cpu[n]+=1
  if e['type']=='jdk.JavaMonitorEnter':monitors[v['monitorClass']['name']].append(duration(v['duration']))
  if e['type']=='jdk.ThreadPark' and names:parks[names[0]]+=1
 def stats(v):
  v=sorted(v);return {'count':len(v),'sum_ms':sum(v),'p50_ms':v[len(v)//2],'p99_ms':v[min(len(v)-1,int(len(v)*.99))],'max_ms':v[-1]}
 result[row['run']]={'stages_over_1ms':{k:stats(v) for k,v in stages.items()},'monitor_events':{k:stats(v) for k,v in monitors.items()},'cpu_samples':samples,'top_inclusive_frames':cpu.most_common(20),'park_top_frames':parks.most_common(8)}
Path(a.output).write_text(json.dumps(result,indent=2)+'\n')
for name,value in result.items():
 if 'miss' in name or 'updates' in name:print(name,json.dumps(value))
