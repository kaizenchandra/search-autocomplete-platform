import json,os,subprocess,time,urllib.request,urllib.error
base=os.environ.get('BASE_URL','http://127.0.0.1:8180')
def get(path):
 with urllib.request.urlopen(base+path) as r:return json.load(r)
def sql(statement):subprocess.run(['docker','compose','-f','search-autocomplete-platform/compose.yml','exec','-T','postgres','psql','-U','autocomplete','-d','autocomplete','-v','ON_ERROR_STOP=1','-c',statement],check=True,stdout=subprocess.DEVNULL)
def await_result(predicate):
 for _ in range(30):
  if predicate():return
  time.sleep(.2)
 raise AssertionError('update not visible within six seconds')
assert get('/api/v1/autocomplete?prefix=spr')['suggestions'][0]['text']=='Spring Boot'
try:urllib.request.urlopen(base+'/api/v1/admin/suggestions');raise AssertionError('admin allowed')
except urllib.error.HTTPError as e:assert e.code==401
try:urllib.request.urlopen(base+'/api/v1/autocomplete?limit=100');raise AssertionError('limit accepted')
except urllib.error.HTTPError as e:assert e.code==400
try:
 sql("begin;select * from corpus_revision for update;insert into suggestions values ('integration-only','public','zzverification term','QUERY','test','en',true,1,0),('integration-only','secret-tenant','zzverification private','QUERY','test','en',true,9999,0);update corpus_revision set version=version+1;commit;")
 await_result(lambda:len(get('/api/v1/autocomplete?prefix=zzverification')['suggestions'])==1)
 for _ in range(3):assert get('/api/v1/autocomplete?prefix=zzverification')['suggestions'][0]['text']=='zzverification term'
 sql("begin;select * from corpus_revision for update;delete from suggestions where id='integration-only';update corpus_revision set version=version+1;commit;")
 await_result(lambda:not get('/api/v1/autocomplete?prefix=zzverification')['suggestions'])
 print('PASS: API, validation, anonymous authorization, tenant isolation, revision update, cached deletion')
finally:sql("begin;select * from corpus_revision for update;delete from suggestions where id='integration-only';update corpus_revision set version=version+1;commit;")
