import argparse,json
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('directory');a=p.parse_args();root=Path(a.directory)
rows=json.loads((root/'runs.json').read_text())
lines=['# Measured API load results','', '|Run|Offered RPS|HTTP RPS|Successful RPS|p50 ms|p95 ms|p99 ms|HTTP errors %|Dropped|Exit|','|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|']
for row in rows:
 m=row['metrics'];lat=m.get('successful_latency',{});fmt=lambda v:'—' if v is None else f'{v:.2f}'
 lines.append('|'+ '|'.join([row['run'],str(row['offered_rps']),fmt(m.get('http_reqs',{}).get('rate',0)),fmt(m.get('successful_requests',{}).get('rate',0)),fmt(lat.get('med')),fmt(lat.get('p(95)')),fmt(lat.get('p(99)')),fmt(100*m.get('http_req_failed',{}).get('value',0)),str(m.get('dropped_iterations',{}).get('count',0)),str(row['k6_exit'])])+'|')
lines+=['','Latencies include only HTTP 200 responses. HTTP RPS includes failures. Dropped iterations were not sent. Exit 99 denotes a failed k6 threshold; all raw summaries are retained. These are short shared-host runs, not capacity guarantees.']
(root/'summary.md').write_text('\n'.join(lines)+'\n');print('\n'.join(lines))
