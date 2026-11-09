"""Use existing Linux crypt bcrypt, never print or persist cleartext passwords."""
import crypt
import secrets

def bcrypt_hash(password, legacy=False):
    if not legacy and not 6 <= len(password) <= 32:
        raise RuntimeError('Operator password must contain 6 to 32 characters')
    if len(password.encode('utf-8')) > 72 or '\x00' in password:
        raise RuntimeError('Password must not contain NUL or exceed 72 UTF-8 bytes (BCrypt limit)')
    if crypt.METHOD_BLOWFISH not in crypt.methods:
        raise RuntimeError('System bcrypt unavailable; no weaker hash fallback allowed')
    result = crypt.crypt(password, crypt.mksalt(crypt.METHOD_BLOWFISH))
    if not result or not result.startswith(('$2a$', '$2b$', '$2y$')):
        raise RuntimeError('bcrypt hashing failed')
    return result

def test_credentials(env, length=24):
    if not 6 <= length <= 32:
        raise RuntimeError('Test password length must be 6 to 32')
    password = secrets.token_hex(16)[:length]
    result = env.copy()
    result['AGENTCV_OPERATOR_PASSWORD_HASH'] = bcrypt_hash(password)
    result['AGENTCV_TEST_PASSWORD_HASH'] = result['AGENTCV_OPERATOR_PASSWORD_HASH']
    result['AGENTCV_TEST_PASSWORD'] = password
    return result, password