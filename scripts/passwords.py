"""Use existing Linux crypt bcrypt, never print or persist cleartext passwords."""
import crypt
import secrets

def bcrypt_hash(password):
    if not 20 <= len(password) <= 64:
        raise RuntimeError('Operator password must contain 20 to 64 characters')
    if crypt.METHOD_BLOWFISH not in crypt.methods:
        raise RuntimeError('System bcrypt unavailable; no weaker hash fallback allowed')
    result = crypt.crypt(password, crypt.mksalt(crypt.METHOD_BLOWFISH))
    if not result or not result.startswith(('$2a$', '$2b$', '$2y$')):
        raise RuntimeError('bcrypt hashing failed')
    return result

def test_credentials(env):
    password = secrets.token_hex(24)
    result = env.copy()
    result['AGENTCV_OPERATOR_PASSWORD_HASH'] = bcrypt_hash(password)
    result['AGENTCV_TEST_PASSWORD_HASH'] = result['AGENTCV_OPERATOR_PASSWORD_HASH']
    result['AGENTCV_TEST_PASSWORD'] = password
    return result, password