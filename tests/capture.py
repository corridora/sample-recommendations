#!/usr/bin/env python3
"""Local OTLP capture + AWS leaf fixture. No credentials/data are sent to Corridora.

The small protobuf reader only decodes the official OTLP fields asserted here;
it is a test utility, not a production telemetry receiver.
"""
import argparse
import base64
import gzip
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import json
from pathlib import Path
import threading
import zlib

def varint(data,pos):
    value=0;shift=0
    while True:
        b=data[pos];pos+=1;value|=(b&127)<<shift
        if b<128:return value,pos
        shift+=7

def fields(data):
    pos=0;out={}
    while pos<len(data):
        tag,pos=varint(data,pos);kind=tag&7;number=tag>>3
        if kind==0:value,pos=varint(data,pos)
        elif kind==2:
            length,pos=varint(data,pos);value=data[pos:pos+length];pos+=length
        elif kind in (1,5):
            size=8 if kind==1 else 4;value=data[pos:pos+size];pos+=size
        else:raise ValueError('Unsupported protobuf wire type')
        out.setdefault(number,[]).append(value)
    return out

def span_records(payload):
    records=[]
    for rs in fields(payload).get(1,[]):
        resource=fields(rs);attrs={}
        for kv in fields(resource.get(1,[b''])[0]).get(1,[]):
            f=fields(kv);attrs[f.get(1,[b''])[0].decode()]=fields(f.get(2,[b''])[0]).get(1,[b''])[0].decode(errors='replace')
        for scope in resource.get(2,[]):
            for span in fields(scope).get(2,[]):
                f=fields(span);records.append({'traceId':f.get(1,[b''])[0].hex(),'spanId':f.get(2,[b''])[0].hex(),'parentSpanId':f.get(4,[b''])[0].hex(),'name':f.get(5,[b''])[0].decode(),'kind':f.get(6,[0])[0],'service':attrs.get('service.name','')})
    return records

def json_spans(envelope):
    records=[]
    for resource in envelope.get('resourceSpans',[]):
        attrs={a['key']:a.get('value',{}).get('stringValue','') for a in resource.get('resource',{}).get('attributes',[])}
        for scope in resource.get('scopeSpans',[]):
            for s in scope.get('spans',[]):records.append({**s,'service':attrs.get('service.name','')})
    return records

class Capture:
    def __init__(self,bundle=None):self.bundle=bundle;self.records=[];self.requests=[];self.lock=threading.Lock();self.fail_exports=False
    def handler(self):
        state=self
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def send(self,status,body=b'',content='application/json'):
                self.send_response(status);self.send_header('Content-Type',content);self.send_header('Access-Control-Allow-Origin',self.headers.get('Origin','*'));self.send_header('Access-Control-Allow-Headers','*');self.send_header('Access-Control-Allow-Methods','POST,GET,OPTIONS');self.end_headers();self.wfile.write(body)
            def do_OPTIONS(self):self.send(204)
            def do_GET(self):
                if self.path.startswith('/corridora-rum.js') and state.bundle:self.send(200,Path(state.bundle).read_bytes(),'application/javascript')
                elif self.path.startswith('/internal/review-summary'):
                    state.requests.append({'path':self.path,'traceparent':self.headers.get('traceparent'),'authorization':self.headers.get('Authorization'),'export_key':self.headers.get('X-CORRIDORA-KEY')});self.send(200,b'{"product_id":"demo-product","rating":4.5,"count":12}')
                elif self.path=='/capture':self.send(200,json.dumps({'spans':state.records,'requests':state.requests}).encode())
                else:self.send(404)
            def do_POST(self):
                body=self.rfile.read(int(self.headers.get('Content-Length',0)));encoding=self.headers.get('Content-Encoding','')
                if state.fail_exports:self.send(503,b'{}');return
                if encoding=='gzip':body=gzip.decompress(body)
                if encoding=='deflate':body=zlib.decompress(body)
                content=self.headers.get('Content-Type','')
                with state.lock:
                    state.requests.append({'path':self.path,'type':content,'bytes':len(body),'key':self.headers.get('X-CORRIDORA-KEY'),'rum_key':self.headers.get('X-CORRIDORA-RUM-KEY')})
                    if self.path.split('?')[0]=='/v1/traces':state.records.extend(json_spans(json.loads(body)) if 'json' in content else span_records(body))
                self.send(200,b'{}' if 'json' in content else b'',content)
        return Handler

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--port',type=int,default=4319);p.add_argument('--rum-bundle');a=p.parse_args()
    ThreadingHTTPServer(('127.0.0.1',a.port),Capture(a.rum_bundle).handler()).serve_forever()
