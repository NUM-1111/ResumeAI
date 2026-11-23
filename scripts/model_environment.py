"""Read the one supported model setting without executing shell expressions."""
from pathlib import Path


def load_model_environment(env, path: Path):
    result = env.copy()
    if not path.exists():
        return result
    if not path.is_file():
        raise RuntimeError('.env must be a regular file')
    for number, raw in enumerate(path.read_text(encoding='utf-8-sig').splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        if line.startswith('export '):
            line = line[7:].strip()
        name, separator, value = line.partition('=')
        if not separator:
            raise RuntimeError('Invalid .env assignment at line ' + str(number))
        if name.strip() != 'DEEPSEEK_API_KEY':
            continue
        value = value.strip()
        if value.startswith(('"', "'")):
            if len(value) < 2 or value[-1] != value[0]:
                raise RuntimeError('Unclosed .env quote at line ' + str(number))
            value = value[1:-1]
        if any(ord(char) < 32 or ord(char) == 127 for char in value):
            raise RuntimeError('Invalid model credential format in .env')
        # Explicit shell environment wins; no interpolation or command execution.
        result.setdefault('DEEPSEEK_API_KEY', value)
    return result