import unittest
from unittest.mock import patch
import local
import postgres17

class PostgreSQL17Tests(unittest.TestCase):
    def test_default_start_never_calls_legacy(self):
        with patch.object(postgres17,'start') as start, patch.object(local,'legacy_db_start') as legacy:
            local.db_start()
            start.assert_called_once();legacy.assert_not_called()

    def test_missing_container_does_not_silently_create(self):
        with patch.object(postgres17,'inspect',return_value=None),patch.object(postgres17,'docker') as docker:
            with self.assertRaisesRegex(RuntimeError,'not provisioned'):postgres17.start()
            docker.assert_not_called()

    def test_verified_container_start_only(self):
        with patch.object(postgres17,'inspect',return_value={'State':{'Running':False}}),patch.object(postgres17,'docker') as docker,patch.object(postgres17.subprocess,'run') as ready:
            ready.return_value.returncode=0
            postgres17.start()
            self.assertEqual(docker.call_args_list[0].args,('start',postgres17.NAME))

if __name__=='__main__':unittest.main()