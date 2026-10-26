import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import manage


class SetupTests(unittest.TestCase):
    def test_setup_preserves_existing_private_and_model_settings(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            (root/'ai-service').mkdir()
            example = 'POSTGRES_USER=helpdesk\nPOSTGRES_DB=helpdesk\nPOSTGRES_PORT=55432\nREDIS_PORT=56379\nS3_PORT=59000\n'
            example += ''.join(f'{key}=\n' for key in ('POSTGRES_PASSWORD','S3_SECRET_KEY','SERVICE_TOKEN','ADMIN_PASSWORD','AGENT_PASSWORD','EMPLOYEE_PASSWORD','HR_PASSWORD'))
            (root/'.env.example').write_text(example,encoding='utf-8')
            model_path = root/'ai-service/.env'
            model_path.write_text('AGENT_LLM_MODEL=user-selected-model\n',encoding='utf-8')
            with patch.object(manage,'ROOT',root),patch.object(manage,'RUNTIME',root/'.runtime'):
                first = manage.setup()
                private = (root/'.env').read_bytes()
                second = manage.setup()
            self.assertEqual(first,second)
            self.assertEqual(private,(root/'.env').read_bytes())
            self.assertEqual('AGENT_LLM_MODEL=user-selected-model\n',model_path.read_text(encoding='utf-8'))
            self.assertNotEqual(first['ADMIN_PASSWORD'],first['EMPLOYEE_PASSWORD'])

    def test_partial_secret_file_fails_without_overwriting(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            path=root/'.env'
            original='POSTGRES_PASSWORD=\n'
            path.write_text(original,encoding='utf-8')
            with patch.object(manage,'ROOT',root),patch.object(manage,'RUNTIME',root/'.runtime'):
                with self.assertRaises(ValueError):
                    manage.setup()
            self.assertEqual(original,path.read_text(encoding='utf-8'))


if __name__=='__main__':
    unittest.main()
