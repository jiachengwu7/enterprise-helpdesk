"""Exercise the three reviewed failure paths against real services, without models."""
import json
import time
import uuid
import urllib.error
import urllib.request
import manage
from smoke import request, multipart, ready


def internal(path, secret, body=None, method=None):
    data=json.dumps(body).encode() if body is not None else None
    req=urllib.request.Request('http://127.0.0.1:8101'+path,data=data,method=method or ('POST' if body is not None else 'GET'),headers={'X-Service-Token':secret,'Content-Type':'application/json'})
    try:
        response=urllib.request.urlopen(req,timeout=30)
    except urllib.error.HTTPError as error:
        response=error
    with response:
        raw=response.read()
        return response.status,json.loads(raw) if response.headers.get_content_type()=='application/json' else raw


def start_ai(env):
    manage.start_process('ai',[manage.python_path(),'-X','utf8','-m','uvicorn','app.main:app','--host','127.0.0.1','--port','8101'],manage.ROOT/'ai-service',env,8101,'http://127.0.0.1:8101/health')


def main():
    values=manage.setup(); env=manage.environment(values)
    admin=request('/auth/login','POST',{'username':'admin','password':values['ADMIN_PASSWORD']})['token']
    login=request('/auth/login','POST',{'username':'employee','password':values['EMPLOYEE_PASSWORD']})
    employee=login['token']; actor=login['user']
    ai=request('/system/status',token=admin)['ai']
    if ai.get('modelConfigured') or ai.get('embeddingConfigured'):
        raise RuntimeError('Review acceptance never permits paid model calls; use offline settings.')
    suffix=uuid.uuid4().hex[:8]
    marker='REVIEWDATA'+suffix
    title='审查验收文档'+suffix
    conversations=[]; doc_id=None
    checks=[]
    try:
        body,ctype=multipart({'title':title,'departmentId':'HR','visibility':'PUBLIC'},'review.md','审查 E982 权限处理说明。原文标记 '+marker)
        doc=request('/documents','POST',body,admin,content_type=ctype); doc_id=doc['id']; ready(admin,doc_id)
        conv=request('/conversations','POST',{'title':'历史撤权验收'},employee); cid=conv['id']; conversations.append(cid)
        answer=request('/conversations/'+cid+'/messages','POST',{'question':'审查 E982 权限处理说明'},employee)
        assert marker in answer['answer']
        body,ctype=multipart({'title':title,'departmentId':'HR','visibility':'DEPARTMENT'},'review.md','新版 HR 部门文档。')
        request('/documents/'+doc_id,'PUT',body,admin,content_type=ctype); ready(admin,doc_id)
        history=request('/conversations/'+cid,token=employee)
        assert marker not in json.dumps(history,ensure_ascii=False)
        assert all(source['documentId']!=doc_id for message in history['messages'] for source in message['sources'])
        checks.append('revocation masks entire old assistant text, not only citations')

        lost=request('/conversations','POST',{'title':'丢失响应验收'},employee); lid=lost['id']; conversations.append(lid)
        allowed=[item['id'] for item in request('/documents',token=employee) if item['status']=='READY']
        code,ai_answer=internal('/internal/chat',values['SERVICE_TOKEN'],{'conversationId':lid,'userId':actor['id'],'departmentId':actor['departmentId'],'role':actor['role'],'allowedDocumentIds':allowed,'question':'VPN E809 丢失响应验收，请 IT 协助','requestTicket':True})
        assert code==200 and ai_answer['proposal']
        # Deliberately bypass Java delivery: only Python has checkpointed this response.
        restored=request('/conversations/'+lid,token=employee)
        again=request('/conversations/'+lid,token=employee)
        assert restored['pendingProposal']['id']==ai_answer['proposal']['id']
        assert len(restored['messages'])==len(again['messages'])==2
        decision={'proposalId':restored['pendingProposal']['id'],'approved':True}
        confirmed=request('/conversations/'+lid+'/confirm','POST',decision,employee)
        replay=request('/conversations/'+lid+'/confirm','POST',decision,employee)
        assert confirmed['ticket']['id']==replay['ticket']['id']
        tid=confirmed['ticket']['id']
        request('/tickets/'+tid,'PATCH',{'status':'IN_PROGRESS'},admin)
        request('/tickets/'+tid,'PATCH',{'status':'RESOLVED','resolution':'丢失响应恢复验收通过'},admin)
        request('/tickets/'+tid,'PATCH',{'status':'CLOSED','rating':5},employee)
        checks.append('lost Python response reconciles pending draft and turns exactly once')

        record=json.loads((manage.RUNTIME/'ai.json').read_text(encoding='utf-8'))
        assert manage.owns_process(record)
        manage.run(['powershell.exe','-NoProfile','-Command',f"Stop-Process -Id {int(record['pid'])} -Force"])
        deadline=time.monotonic()+10
        while manage.listening(8101) and time.monotonic()<deadline:
            time.sleep(.1)
        assert not manage.listening(8101)
        request('/documents/'+doc_id,'DELETE',token=admin)
        assert doc_id not in [item['id'] for item in request('/documents',token=admin)]
        start_ai(env)
        deadline=time.monotonic()+90
        while time.monotonic()<deadline:
            code,_=internal('/internal/documents/'+doc_id+'/download',values['SERVICE_TOKEN'])
            if code==404:
                break
            assert code==200
            time.sleep(1)
        assert code==404,'Deferred deletion did not reach Python after recovery'
        checks.append('delete during AI outage revokes access immediately and retries physical cleanup')
        doc_id=None
    finally:
        if not manage.listening(8101):
            start_ai(env)
        if doc_id:
            try: request('/documents/'+doc_id,'DELETE',token=admin)
            except AssertionError: pass
        for cid in conversations:
            request('/conversations/'+cid,'DELETE',token=employee)
        request('/auth/logout','POST',token=employee)
        request('/auth/logout','POST',token=admin)
    (manage.ROOT/'docs/review-regression-result.json').write_text(json.dumps({'passed':checks,'cloudCalls':False},ensure_ascii=False,indent=2),encoding='utf-8')
    for check in checks:
        print('PASS '+check)


if __name__=='__main__':
    main()
