"""Real infrastructure smoke. Never invokes a configured paid model by default."""
from __future__ import annotations
import argparse
import json
import time
import uuid
import urllib.error
import urllib.request
from pathlib import Path

from manage import ROOT, read_env

BASE = 'http://127.0.0.1:8180/api'


def request(path, method='GET', body=None, token=None, expected=200, content_type='application/json'):
    data = json.dumps(body).encode() if body is not None and content_type=='application/json' else body
    headers = {'Content-Type':content_type}
    if token:
        headers['Authorization'] = f'Bearer {token}'
    req = urllib.request.Request(BASE+path, data=data, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(req, timeout=90)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        status = response.status
        raw = response.read()
        if status != expected:
            # Never print payloads: they may include user data.
            raise AssertionError(f'{method} {path}: expected {expected}, got {status}')
        if response.headers.get_content_type()!='application/json':
            return raw
        payload = json.loads(raw)
        if expected==200:
            assert payload['code']==200, f'{path} failed business response'
        return payload.get('data')


def multipart(fields, filename, content):
    boundary = 'helpdesk'+uuid.uuid4().hex
    chunks=[]
    for key,value in fields.items():
        chunks.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{key}"\r\n\r\n{value}\r\n'.encode())
    chunks += [f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n'.encode(),content.encode('utf-8'),f'\r\n--{boundary}--\r\n'.encode()]
    return b''.join(chunks),f'multipart/form-data; boundary={boundary}'


def upload(token,title,text,department='IT',doc_id=None):
    body,ctype=multipart({'title':title,'departmentId':department,'visibility':'DEPARTMENT'},'guide.md',text)
    return request('/documents'+('/'+doc_id if doc_id else ''),'PUT' if doc_id else 'POST',body,token,content_type=ctype)


def ready(token,doc_id):
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        doc=next(item for item in request('/documents',token=token) if item['id']==doc_id)
        if doc['status']=='READY':
            return doc
        if doc['status']=='FAILED':
            raise AssertionError('Document ingestion failed; inspect safe status in UI')
        time.sleep(1)
    raise AssertionError('Document did not become READY')


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--allow-model',action='store_true',help='Explicitly permit real cloud embedding/chat calls during this run')
    args=parser.parse_args()
    settings=read_env(ROOT/'.env')
    tokens={}
    users={}
    checks=[]
    for name,key in [('admin','ADMIN_PASSWORD'),('agent','AGENT_PASSWORD'),('employee','EMPLOYEE_PASSWORD'),('hr','HR_PASSWORD')]:
        login=request('/auth/login','POST',{'username':name,'password':settings[key]})
        tokens[name]=login['token']; users[name]=login['user']
    checks.append('four-role login')
    request('/documents',expected=401)
    request('/users',token=tokens['employee'],expected=403)
    checks.append('unauthenticated and employee administration denied')
    status=request('/system/status',token=tokens['admin'])
    ai=status['ai']
    if (ai.get('modelConfigured') or ai.get('embeddingConfigured')) and not args.allow_model:
        raise RuntimeError('Cloud model is configured. Refusing paid test calls. Use isolated offline configuration or explicitly --allow-model.')
    suffix=uuid.uuid4().hex[:8]
    created_docs=[]; conversations=[]
    try:
        doc=upload(tokens['admin'],'Smoke VPN '+suffix,'VPN连接错误 E809：请先检查企业网关地址，再联系 IT 支持。测试标记：ALPHA'+suffix)
        doc_id=doc['id']; created_docs.append(doc_id)
        ready(tokens['admin'],doc_id)
        assert doc_id in [x['id'] for x in request('/documents',token=tokens['employee'])]
        assert doc_id not in [x['id'] for x in request('/documents',token=tokens['hr'])]
        request('/documents/'+doc_id+'/download',token=tokens['hr'],expected=403)
        downloaded=request('/documents/'+doc_id+'/download',token=tokens['employee'])
        assert b'ALPHA' in downloaded
        checks.append('real S3 upload/download and department document isolation')

        conv=request('/conversations','POST',{'title':'Smoke '+suffix},tokens['employee']); cid=conv['id']; conversations.append(cid)
        request('/conversations/'+cid,token=tokens['hr'],expected=403)
        answer=request('/conversations/'+cid+'/messages','POST',{'question':'VPN E809 怎么办？'},tokens['employee'])
        assert any(source['documentId']==doc_id for source in answer['sources'])
        if not args.allow_model:
            assert answer['mode']=='retrieval-only'
        checks.append('LangGraph retrieval and source citation, conversation ownership')

        draft=request('/conversations/'+cid+'/messages','POST',{'question':'VPN E809 仍未解决，请建工单 Smoke-'+suffix,'requestTicket':True},tokens['employee'])
        proposal=draft['proposal']; assert proposal and proposal['id']
        request('/conversations/'+cid+'/confirm','POST',{'proposalId':proposal['id'],'approved':True},tokens['hr'],expected=403)
        confirmed=request('/conversations/'+cid+'/confirm','POST',{'proposalId':proposal['id'],'approved':True},tokens['employee'])
        replay=request('/conversations/'+cid+'/confirm','POST',{'proposalId':proposal['id'],'approved':True},tokens['employee'])
        ticket=confirmed['ticket']; assert ticket['id']==replay['ticket']['id']
        tid=ticket['id']
        request('/tickets/'+tid,token=tokens['hr'],expected=403)
        request('/tickets/'+tid,'PATCH',{'status':'CLOSED','rating':5},tokens['employee'],expected=409)
        request('/tickets/'+tid,'PATCH',{'status':'IN_PROGRESS','assigneeId':users['agent']['id']},tokens['agent'])
        request('/tickets/'+tid+'/comments','POST',{'content':'已检查网关配置'},tokens['agent'])
        request('/tickets/'+tid,'PATCH',{'status':'RESOLVED','resolution':'修正企业网关地址，恢复访问'},tokens['agent'])
        closed=request('/tickets/'+tid,'PATCH',{'status':'CLOSED','rating':5},tokens['employee'])
        assert closed['status']=='CLOSED' and closed['rating']==5
        checks.append('human approval and duplicate-confirm idempotency; full ticket lifecycle and rating')

        newdoc=upload(tokens['admin'],'Smoke VPN '+suffix,'新版 VPN E809 操作指南：重新导入配置。测试标记：BETA'+suffix,doc_id=doc_id)
        assert newdoc['version']==doc['version']+1
        ready(tokens['admin'],doc_id)
        updated=request('/conversations/'+cid+'/messages','POST',{'question':'VPN E809 最新操作指南'},tokens['employee'])
        matching=[item for item in updated['sources'] if item['documentId']==doc_id]
        assert matching and all(item['version']==newdoc['version'] for item in matching)
        assert not any('ALPHA'+suffix in item['content'] for item in updated['sources'])
        request('/documents/'+doc_id,'DELETE',token=tokens['admin']); created_docs.remove(doc_id)
        after=request('/conversations/'+cid+'/messages','POST',{'question':'VPN E809 最新操作指南'},tokens['employee'])
        assert all(item['documentId']!=doc_id for item in after['sources'])
        checks.append('version replacement and deletion remove stale retrieval evidence')
        history=request('/conversations/'+cid,token=tokens['employee'])
        assert len(history['messages'])>=6
        checks.append('conversation history persisted')
    finally:
        for doc_id in created_docs:
            request('/documents/'+doc_id,'DELETE',token=tokens['admin'])
        for cid in conversations:
            request('/conversations/'+cid,'DELETE',token=tokens['employee'])
        for token in tokens.values():
            request('/auth/logout','POST',token=token)
    report={'passed':checks,'realModelAllowed':args.allow_model,'time':time.strftime('%Y-%m-%dT%H:%M:%S')}
    (ROOT/'docs'/'smoke-result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    for check in checks:
        print('PASS '+check)


if __name__=='__main__':
    main()
