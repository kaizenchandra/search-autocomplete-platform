"""Reversible fault probe against only the worker PIDs created by serve.py."""
import argparse,json,os,signal,subprocess,time,urllib.request,urllib.error
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--state',default='/tmp/search-load-env.json');p.add_argument('--output',required=True);a=p.parse_args();state=json.loads(Path(a.state).read_text());roles=state['roles']
def request(url,payload=None):
 req=urllib.request.Request(url,data=None if payload is None else json.dumps(payload).encode(),headers={'Content-Type':'application/json'})
 try:
  with urllib.request.urlopen(req,timeout=3) as response:return response.status,json.load(response)
 except urllib.error.HTTPError as error:return error.code,None
# Wait for settled revision convergence after the update writer exits.
time.sleep(2)
checks=0
for prefix in ['c','catalog phone 0','catalog phone 19','catalog phone 00001','catalog phone 07007','absent-catalog']:
 query='/api/v1/autocomplete?category=load&limit=20&prefix='+urllib.parse.quote(prefix)
 ca,co=request(roles['coordinator']['url']+query);la,lo=request(roles['local']['url']+query)
 assert ca==la==200,(ca,la)
 assert co['indexVersion']==lo['indexVersion'] and co['suggestions']==lo['suggestions'],prefix
 checks+=1
_,health=request(roles['worker1']['url']+'/api/v1/autocomplete/health')
internal={'version':health['version'],'epoch':'normalized-hash-v1:2','shard':1,'tenant':'public','category':'load','language':'en','prefix':'c','limit':10,'fuzzy':False}
unauthorized,_=request(roles['worker1']['url']+'/internal/shards/query',internal);assert unauthorized==403
partial,_=request(roles['worker1']['url']+'/api/v1/autocomplete?prefix=c');assert partial==503
pid=roles['worker0']['pid'];command=subprocess.check_output(['ps','-p',str(pid),'-o','command='],text=True)
assert 'autocomplete-1.0.0.jar' in command and f"--server.port={state['base_port']+1}" in command,'Refusing to pause an unrelated process'
start=time.monotonic();os.kill(pid,signal.SIGSTOP)
try:
 status,_=request(roles['coordinator']['url']+'/api/v1/autocomplete?category=load&prefix=outage-probe-uncached&limit=2')
 elapsed=(time.monotonic()-start)*1000
 assert status==503,status
finally:os.kill(pid,signal.SIGCONT)
result={'exact_local_vs_remote_comparisons':checks,'unauthorized_internal_status':unauthorized,'worker_public_partial_status':partial,'paused_worker_uncached_status':status,'fault_response_ms':elapsed,'worker_resumed':True}
Path(a.output).write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
