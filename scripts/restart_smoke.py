"""Verify a real process restart preserves identity/history and pending approval."""
import json
import manage
from smoke import request


def main():
    values=manage.setup()
    env=manage.environment(values)
    employee=request('/auth/login','POST',{'username':'employee','password':values['EMPLOYEE_PASSWORD']})['token']
    admin=request('/auth/login','POST',{'username':'admin','password':values['ADMIN_PASSWORD']})['token']
    state=request('/system/status',token=admin)['ai']
    if state.get('modelConfigured') or state.get('embeddingConfigured'):
        raise RuntimeError('Restart acceptance requires offline model settings; it never makes paid model calls.')
    conv=request('/conversations','POST',{'title':'重启恢复验收'},employee)
    cid=conv['id']
    try:
        answer=request('/conversations/'+cid+'/messages','POST',{'question':'VPN E809 问题，重启恢复验收','requestTicket':True},employee)
        proposal=answer['proposal']
        assert proposal
        before=request('/conversations/'+cid,token=employee)
        manage.stop_services()
        manage.start(env)
        after=request('/conversations/'+cid,token=employee)
        assert after['pendingProposal']['id']==proposal['id']
        assert [item['id'] for item in before['messages']]==[item['id'] for item in after['messages']]
        decision={'proposalId':proposal['id'],'approved':True}
        first=request('/conversations/'+cid+'/confirm','POST',decision,employee)
        second=request('/conversations/'+cid+'/confirm','POST',decision,employee)
        assert first['ticket']['id']==second['ticket']['id']
        tid=first['ticket']['id']
        request('/tickets/'+tid,'PATCH',{'status':'IN_PROGRESS'},admin)
        request('/tickets/'+tid,'PATCH',{'status':'RESOLVED','resolution':'重启恢复和幂等确认验证通过'},admin)
        request('/tickets/'+tid,'PATCH',{'status':'CLOSED','rating':5},employee)
        report={'passed':['opaque login token persists across restart','conversation history persists','pending proposal and LangGraph checkpoint persist','approval resumes graph after process restart','repeated approval returns same ticket'],'cloudCalls':False}
        (manage.ROOT/'docs/restart-result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        for check in report['passed']:
            print('PASS '+check)
    finally:
        request('/conversations/'+cid,'DELETE',token=employee)
        request('/auth/logout','POST',token=employee)
        request('/auth/logout','POST',token=admin)


if __name__=='__main__':
    main()
