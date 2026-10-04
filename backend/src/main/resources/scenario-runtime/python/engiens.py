"""Engiens check API. Owned by Engiens, never generated: hidden checks register with @check("name")."""

_CHECKS = []


def check(name):
    def register(fn):
        _CHECKS.append((name, fn))
        return fn
    return register
