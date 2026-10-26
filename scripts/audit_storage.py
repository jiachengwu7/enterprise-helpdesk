"""Read-only audit of deletion completion. Run with the AI virtualenv Python."""
import json
import os
import sys
import manage

os.environ.update(manage.environment(manage.read_env(manage.ROOT/'.env')))
sys.path.insert(0,str(manage.ROOT/'ai-service'))
import psycopg
from app.config import Settings
from app.storage import ObjectStorage


def main():
    settings=Settings()
    with psycopg.connect(settings.database_url) as conn:
        deleted={row[0] for row in conn.execute('SELECT id FROM ai_documents WHERE deleted').fetchall()}
        deleted_chunks=conn.execute('SELECT count(*) FROM ai_chunks c JOIN ai_documents d ON d.id=c.document_id WHERE d.deleted').fetchone()[0]
        pending_gc=conn.execute('SELECT count(*) FROM ai_gc').fetchone()[0]
        pending_java=conn.execute('SELECT count(*) FROM hd_business.hd_documents WHERE active=false AND cleanup_done=false').fetchone()[0]
    storage=ObjectStorage(settings)
    deleted_objects=0
    objects=0
    for page in storage.client.get_paginator('list_objects_v2').paginate(Bucket=storage.bucket,Prefix='documents/'):
        for item in page.get('Contents',[]):
            objects+=1
            if item['Key'].split('/')[1] in deleted:
                deleted_objects+=1
    report={'deletedDocumentChunksRemaining':deleted_chunks,'deletedDocumentObjectsRemaining':deleted_objects,'pendingS3Garbage':pending_gc,'pendingJavaCleanup':pending_java,'storedOriginalObjects':objects}
    (manage.ROOT/'docs/storage-audit-result.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps(report))
    assert deleted_chunks==deleted_objects==pending_gc==pending_java==0


if __name__=='__main__':
    main()
