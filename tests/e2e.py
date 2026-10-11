#!/usr/bin/env python3
"""Exercise the real app, local OTLP sink, AWS leaf and browser. Uses mock keys only."""
import base64
import datetime
from http.server import ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
from capture import Capture

ROOT=Path(__file__).resolve().parents[1]
def api(path,data=None,auth=True):
    req=urllib.request.Request('http://127.0.0.1:4107'+path,data=None if data is None else json.dumps(data).encode(),headers={'Content-Type':'application/json',**({'Authorization':'Bearer local-test-control'} if auth else {})})
    try:
        with urllib.request.urlopen(req,timeout=15) as r:return r.status,json.load(r)
    except urllib.error.HTTPError as e:return e.code,json.load(e)

def norm(value):
    if not value:return ''
    if all(c in '0123456789abcdef' for c in value.lower()) and len(value) in (16,32):return value.lower()
    try:return base64.b64decode(value).hex()
    except Exception:return value

def main():
    bundle=os.environ.get('RUM_BUNDLE_PATH')
    capture=Capture(bundle)
    server=ThreadingHTTPServer(('127.0.0.1',4319),capture.handler())
    threading.Thread(target=server.serve_forever,daemon=True).start()
    env=os.environ|{'SAMPLE_MODE':'local','PORT':'4107','DEMO_CONTROL_TOKEN':'local-test-control','CORRIDORA_INGEST_KEY':'local-test-ingest','CORRIDORA_RUM_KEY':'local-test-rum' if bundle else '',
      'CORRIDORA_OTLP_ENDPOINT':'http://127.0.0.1:4319','CORRIDORA_RUM_SCRIPT_URL':'http://127.0.0.1:4319/corridora-rum.js',
      'REVIEW_SUMMARY_URL':'http://127.0.0.1:4319/internal/review-summary','DOWNSTREAM_AUTH_TOKEN':'local-test-dependency','SERVICE_VERSION':'local-validation-build',
      'SAMPLE_CSP':"default-src 'self';script-src 'self' http://127.0.0.1:4319;connect-src 'self' http://127.0.0.1:4319;style-src 'self';img-src 'self' data:",
      'BASE_URL':'http://127.0.0.1:4107','DEMO_DAILY_START_UTC':(datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(hours=12)).strftime('%H:%M')}
    java=shutil.which('java')
    tools=list((ROOT/'.tools').glob('jdk-*/Contents/Home/bin/java'))
    if tools:java=str(tools[0])
    if not env.get('PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH'):
        candidates=sorted((Path.home()/'Library/Caches/ms-playwright').glob('chromium-*/chrome-*/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing'))
        if candidates:env['PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH']=str(candidates[-1])
    with tempfile.TemporaryDirectory(prefix='recommendations-test-') as temp:
        log=open(Path(temp)/'app.log','w+')
        child=subprocess.Popen([java,'-jar',str(ROOT/'target/quarkus-app/quarkus-run.jar')],cwd=temp,env=env,stdout=log,stderr=log)
        try:
            for _ in range(100):
                try:
                    if api('/healthz')[0]==200:break
                except Exception:pass
                if child.poll() is not None:raise RuntimeError('Java service exited before readiness')
                time.sleep(.1)
            else:raise RuntimeError('Service did not start')
            assert api('/api/demo/scenario',{'phase':'regression','duration_seconds':10},False)[0]==401
            assert api('/api/demo/scenario',{'phase':'regression','duration_seconds':601})[0]==400
            assert api('/api/demo/scenario',{'phase':'regression','duration_seconds':1})[0]==200
            time.sleep(1.1)
            assert api('/api/demo/status')[1]['phase']=='healthy'  # Outside natural daily window in CI.
            assert api('/api/refresh?revision=fixture-refresh-v2',{})[0]==200
            assert api('/api/recommendations')[1]['model_revision']=='fixture-refresh-v2'
            api('/api/refresh?revision=fixture-v1',{})
            upstream_trace='1234567890abcdef1234567890abcdef';upstream_span='1234567890abcdef'
            message={'message':{'data':base64.b64encode(b'{"model_revision":"fixture-v1"}').decode(),'attributes':{'traceparent':f'00-{upstream_trace}-{upstream_span}-01'}}}
            assert api('/internal/pubsub',message)[0]==200
            native={'logName':'projects/test/logs/run.googleapis.com%2Frequests','httpRequest':{'requestMethod':'GET','status':200,'latency':'0.123s','requestUrl':'https://example.invalid/?secret=do-not-copy'}}
            provider_envelope={'message':{'data':base64.b64encode(json.dumps(native).encode()).decode()}}
            assert api('/internal/provider-logs',provider_envelope)[0]==200
            capture.fail_exports=True
            failed_started=time.monotonic()
            try:
                assert api('/internal/provider-logs',provider_envelope)[0]==503,'Do not ACK a failed native-log export'
                assert time.monotonic()-failed_started<4.5,'Forwarder must return within its bounded export lifecycle'
            finally:capture.fail_exports=False
            for retry in range(4):
                code,_=api('/internal/provider-logs',provider_envelope)
                if code==200:break
                time.sleep(.5)
            assert code==200,'Recovered exporter must acknowledge the retried native log'
            subprocess.run(['npm','run','test:e2e'],cwd=ROOT/'web',env=env,check=True)
            time.sleep(.3)
            paths={r['path'].split('?')[0] for r in capture.requests}
            assert {'/v1/traces','/v1/logs','/v1/metrics'} <= paths,paths
            assert all(r.get('key')=='local-test-ingest' for r in capture.requests if r.get('type')=='application/x-protobuf')
            consumers=[s for s in capture.records if s.get('name')=='model.refresh consume']
            assert any(norm(s['traceId'])==upstream_trace and norm(s['parentSpanId'])==upstream_span for s in consumers)
            leaves=[r for r in capture.requests if r['path'].startswith('/internal/review-summary')]
            assert leaves and all(r['traceparent'] and r['authorization']=='Bearer local-test-dependency' and not r.get('export_key') for r in leaves)
            pairs=[]
            if bundle:
                clients=[s for s in capture.records if s.get('service')=='sample-recommendations-web' and s.get('kind') in (3,'SPAN_KIND_CLIENT')]
                servers=[s for s in capture.records if s.get('name')=='GET /api/recommendations' and s.get('kind') in (2,'SPAN_KIND_SERVER')]
                pairs=[{'trace_id':norm(s['traceId']),'client_span_id':norm(c['spanId']),'server_span_id':norm(s['spanId'])} for c in clients for s in servers if norm(c['traceId'])==norm(s['traceId']) and norm(c['spanId'])==norm(s.get('parentSpanId',''))]
                assert pairs,f'No actual RUM CLIENT → SERVER match. Clients={len(clients)}, servers={len(servers)}'
                print('Actual Corridora RUM CLIENT → Java SERVER pairs:',len(pairs))
            else:print('RUM_BUNDLE_PATH absent: browser journey passed; browser/server parentage remains unverified.')
            summary={'healthy_regression_recovery':True,'native_export_retry_verified':True,'heartbeat_persistence_verified':True,'signals':sorted(paths),'spans':len(capture.records),'rum_parentage_verified':bool(pairs),'rum_pairs':pairs,'pubsub_parentage_verified':True,'review_leaf_calls':len(leaves),'stored_corridora_query_verified':False}
            (ROOT/'.local').mkdir(mode=0o700,exist_ok=True);(ROOT/'.local/validation-summary.json').write_text(json.dumps(summary,indent=2)+'\n')
            print(json.dumps(summary,indent=2))
        except Exception:
            log.flush();log.seek(0);print(log.read()[-6000:]);raise
        finally:
            child.terminate()
            try:child.wait(timeout=5)
            except subprocess.TimeoutExpired:child.kill()
            log.close();server.shutdown();server.server_close()

if __name__=='__main__':main()
