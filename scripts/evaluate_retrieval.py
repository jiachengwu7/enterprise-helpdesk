"""Tiny labelled acceptance set; not a statistically meaningful benchmark."""
import argparse
import json
import time
from manage import ROOT, read_env
from smoke import request


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--allow-model',action='store_true')
    args=parser.parse_args()
    values=read_env(ROOT/'.env')
    keys={'admin':'ADMIN_PASSWORD','agent':'AGENT_PASSWORD','employee':'EMPLOYEE_PASSWORD','hr':'HR_PASSWORD'}
    tokens={name:request('/auth/login','POST',{'username':name,'password':values[key]})['token'] for name,key in keys.items()}
    rows=[]
    try:
        status=request('/system/status',token=tokens['admin'])['ai']
        if (status.get('modelConfigured') or status.get('embeddingConfigured')) and not args.allow_model:
            raise RuntimeError('Model configured; evaluation refuses paid calls without --allow-model.')
        cases=json.loads((ROOT/'samples/retrieval-eval.json').read_text(encoding='utf-8'))
        for case in cases:
            token=tokens[case['user']]
            conversation=request('/conversations','POST',{'title':'检索评测'},token)
            try:
                started=time.perf_counter()
                answer=request('/conversations/'+conversation['id']+'/messages','POST',{'question':case['question']},token)
                elapsed=round((time.perf_counter()-started)*1000,1)
                titles={source['title'] for source in answer['sources']}
                hit=case['expectedTitle'] in titles if case['expectedTitle'] else None
                scoped=not case.get('forbiddenTitle') or case['forbiddenTitle'] not in titles
                rows.append({**case,'retrievedTitles':sorted(titles),'hit':hit,'scopePassed':scoped,'latencyMs':elapsed,'mode':answer['mode']})
            finally:
                request('/conversations/'+conversation['id'],'DELETE',token=token)
    finally:
        for token in tokens.values():
            request('/auth/logout','POST',token=token)
    relevant=[row for row in rows if row['hit'] is not None]
    result={'note':'6人工编写的小样本；只用于功能回归，不代表真实企业数据集性能或模型质量。','recallAtReturnedK':sum(row['hit'] for row in relevant)/len(relevant),'permissionPassRate':sum(row['scopePassed'] for row in rows)/len(rows),'meanLatencyMs':round(sum(row['latencyMs'] for row in rows)/len(rows),1),'cases':rows}
    (ROOT/'docs/retrieval-eval-result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({key:value for key,value in result.items() if key!='cases'},ensure_ascii=False))
    if not all(row['scopePassed'] and row['hit'] is not False for row in rows):
        raise SystemExit(1)


if __name__=='__main__':
    main()
