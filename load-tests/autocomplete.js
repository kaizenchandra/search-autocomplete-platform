import http from 'k6/http';
import exec from 'k6/execution';
import {check} from 'k6';
import {Counter,Trend,Rate} from 'k6/metrics';
const success=new Counter('successful_requests');
const okLatency=new Trend('successful_latency',true);
const zeros=new Rate('zero_results');
const statuses=new Counter('response_status');
const rps=Number(__ENV.RPS||1000);
const workload=__ENV.WORKLOAD||'cache-hit';
export const options={scenarios:{search:{executor:'constant-arrival-rate',rate:rps,timeUnit:'1s',duration:__ENV.DURATION||'10s',preAllocatedVUs:Number(__ENV.VUS||100),maxVUs:Number(__ENV.MAX_VUS||300),gracefulStop:'5s'}},summaryTrendStats:['avg','min','med','p(95)','p(99)','max'],thresholds:{'response_status{status:200}':['count>=0'],'response_status{status:429}':['count>=0'],'response_status{status:503}':['count>=0'],'response_status{status:500}':['count>=0'],'response_status{status:0}':['count>=0'],http_req_failed:[`rate<${__ENV.ERROR_BUDGET||0.01}`],successful_latency:[`p(99)<${__ENV.P99_MS||50}`]}};
export default function(){
 const iteration=exec.scenario.iterationInTest+Number(__ENV.OFFSET||0);
 let prefix='catalog phone 0';let limit=10;let fuzzy=false;
 if(workload==='cache-miss'){prefix=`catalog phone ${String(iteration%20000).padStart(5,'0')}`;limit=1+Math.floor(iteration/20000)%20;}
 else if(workload==='short-prefix')prefix='c';
 else if(workload==='fuzzy'){prefix='catalpg phone 0';fuzzy=true;}
 else if(workload==='mixed'){prefix=['catalog phone 0','c','catalog phone 19','catalog phone 003','catalog phone 19999'][iteration%5];}
 const url=`${__ENV.BASE_URL||'http://127.0.0.1:8080'}/api/v1/autocomplete?prefix=${encodeURIComponent(prefix)}&category=load&limit=${limit}&fuzzy=${fuzzy}`;
 const response=http.get(url,{timeout:'2s',tags:{name:workload}});
 statuses.add(1,{status:String(response.status)});
 if(response.status===200){success.add(1);okLatency.add(response.timings.duration);let body;try{body=response.json();}catch{}zeros.add(!body||body.suggestions.length===0);check(body,{'bounded valid response':b=>b&&Array.isArray(b.suggestions)&&b.suggestions.length<=limit&&typeof b.indexVersion==='string'});}
 check(response,{'status 200':r=>r.status===200});
}
