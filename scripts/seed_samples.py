"""Import the three repository sample documents through the authenticated API."""
import argparse
from manage import ROOT, read_env
from smoke import request, multipart, ready


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--allow-model', action='store_true')
    args = parser.parse_args()
    settings = read_env(ROOT / '.env')
    login = request('/auth/login', 'POST', {'username':'admin','password':settings['ADMIN_PASSWORD']})
    token = login['token']
    try:
        status = request('/system/status', token=token)
        if status['ai'].get('embeddingConfigured') and not args.allow_model:
            raise RuntimeError('Embedding model configured; use --allow-model to explicitly allow indexing sample documents with the cloud provider.')
        existing = {doc['title'] for doc in request('/documents', token=token)}
        for filename, department, visibility in [('IT-VPN操作手册.md','IT','DEPARTMENT'),('HR请假与考勤.md','HR','DEPARTMENT'),('公共帮助中心.md','IT','PUBLIC')]:
            title = filename[:-3]
            if title in existing:
                print(f'Skipped existing sample: {title}')
                continue
            body, ctype = multipart({'title':title,'departmentId':department,'visibility':visibility}, filename, (ROOT/'samples'/filename).read_text(encoding='utf-8'))
            doc = request('/documents','POST',body,token,content_type=ctype)
            ready(token,doc['id'])
            print(f'Imported: {title}')
    finally:
        request('/auth/logout','POST',token=token)


if __name__=='__main__':
    main()
