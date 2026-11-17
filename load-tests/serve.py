"""Start an isolated four-JVM benchmark environment; Ctrl-C terminates only owned processes."""
import argparse,hashlib,json,os,socket,subprocess,time,urllib.request
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--base-port',type=int,default=33240);p.add_argument('--state',default='/tmp/search-load-env.json');p.add_argument('--profile-dir');p.add_argument('--lifetime',type=int,default=900);a=p.parse_args()
root=Path(__file__).resolve().parents[1];jar=root/'backend/target/autocomplete-1.0.0.jar'
java=Path(os.environ.get('JAVA_HOME','/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home'))/'bin/java'
for port in range(a.base_port,a.base_port+4):
 with socket.socket() as sock:sock.bind(('127.0.0.1',port))
secret='local-benchmark-shard-secret-at-least-32-characters'
env={**os.environ,'DATABASE_URL':'jdbc:postgresql://127.0.0.1:55432/autocomplete','REDIS_PORT':'56379'}
processes=[];logs=[]
try:
 for role,offset,owner in [('worker0',1,0),('worker1',2,1),('coordinator',0,-2),('local',3,-1)]:
  log=Path('/tmp')/f'search-load-{role}.log';stream=log.open('w');logs.append(stream)
  cmd=[str(java),'-Xms128m','-Xmx512m','-XX:+UseG1GC','-jar',str(jar),f'--server.port={a.base_port+offset}','--server.address=127.0.0.1','--index.shards=2',f'--index.shard-id={owner}',f'--index.internal-secret={secret}','--rate-limit.rps=10000','--rate-limit.burst=10000']
  if a.profile_dir:
   profile=Path(a.profile_dir).resolve();profile.mkdir(parents=True,exist_ok=True);settings=root/'profiling/search.jfc';cmd.insert(4,f'-XX:StartFlightRecording=filename={profile/role}.jfr,settings={settings},dumponexit=true,maxsize=128m')
  if role=='coordinator':cmd.append(f'--index.workers=http://127.0.0.1:{a.base_port+1},http://127.0.0.1:{a.base_port+2}')
  process=subprocess.Popen(cmd,env=env,stdout=stream,stderr=subprocess.STDOUT);processes.append((role,process,a.base_port+offset))
 state={'artifact_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'base_port':a.base_port,'roles':{role:{'pid':proc.pid,'url':f'http://127.0.0.1:{port}'} for role,proc,port in processes},'heap':'512m per JVM','shards':2,'rate_limit_rps':10000,'burst':10000}
 Path(a.state).write_text(json.dumps(state,indent=2)+'\n')
 for role,proc,port in processes:
  deadline=time.monotonic()+90
  while True:
   if proc.poll() is not None:raise RuntimeError(f'{role} exited; inspect /tmp/search-load-{role}.log')
   try:
    with urllib.request.urlopen(f'http://127.0.0.1:{port}/api/v1/autocomplete/health',timeout=1) as response:
     if response.status==200:
      state['roles'][role]['initial_version']=json.load(response)['version'];break
   except Exception:
    if time.monotonic()>deadline:raise RuntimeError(f'{role} readiness timeout')
    time.sleep(.25)
 Path(a.state).write_text(json.dumps(state,indent=2)+'\n')
 print(json.dumps({'ready':True,**state}),flush=True)
 deadline=time.monotonic()+a.lifetime
 while time.monotonic()<deadline:
  if any(proc.poll() is not None for _,proc,_ in processes):raise RuntimeError('A benchmark JVM exited')
  time.sleep(1)
except KeyboardInterrupt:pass
finally:
 for _,proc,_ in processes:proc.terminate()
 for _,proc,_ in processes:
  try:proc.wait(timeout=10)
  except subprocess.TimeoutExpired:proc.kill();proc.wait()
 for stream in logs:stream.close()
