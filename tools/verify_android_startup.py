"""Verify startup class definitions in the final debug APK (not just DEX references).

Run before installing a local APK. A successful compile alone does not detect a
truncated incremental deployment missing Hilt's generated application initializer.
Release artifacts have a separate R8 entry point check because names may change.
"""
from __future__ import annotations

import argparse
import re
import struct
import zipfile
from pathlib import Path

REQUIRED = frozenset({
    'Lconectate/com/SocialApp;',
    'Lconectate/com/Hilt_SocialApp;',
    'Lconectate/com/Hilt_SocialApp$1;',
    'Lconectate/com/MainActivity;',
})


def dex_definitions(data: bytes) -> set[str]:
    if len(data) < 112 or not re.fullmatch(rb'dex\n0(?:35|37|38|39|40)\x00', data[:8]):
        raise ValueError('Unsupported or incomplete DEX header')
    def uint(offset):
        if offset < 0 or offset + 4 > len(data):
            raise ValueError('DEX offset outside file')
        return struct.unpack_from('<I', data, offset)[0]
    if uint(32) != len(data) or uint(40) != 0x12345678:
        raise ValueError('DEX size or byte order mismatch')
    string_count, string_offset = uint(56), uint(60)
    type_count, type_offset = uint(64), uint(68)
    class_count, class_offset = uint(96), uint(100)
    if any(start + count * stride > len(data) for count, start, stride in (
        (string_count, string_offset, 4), (type_count, type_offset, 4), (class_count, class_offset, 32))):
        raise ValueError('Truncated DEX index table')
    definitions = set()
    for index in range(class_count):
        type_index = uint(class_offset + index * 32)
        if type_index >= type_count:
            raise ValueError('Invalid class type index')
        string_index = uint(type_offset + type_index * 4)
        if string_index >= string_count:
            raise ValueError('Invalid descriptor string index')
        offset = uint(string_offset + string_index * 4)
        for _ in range(5):
            if offset >= len(data):
                raise ValueError('Truncated descriptor length')
            byte = data[offset]
            offset += 1
            if byte < 0x80:
                break
        else:
            raise ValueError('Invalid descriptor length')
        end = data.find(b'\x00', offset)
        if end < 0:
            raise ValueError('Unterminated descriptor')
        definitions.add(data[offset:end].decode('utf-8', errors='replace'))
    return definitions


def verify(apk: Path) -> set[str]:
    definitions = set()
    with zipfile.ZipFile(apk) as archive:
        for name in archive.namelist():
            if re.fullmatch(r'classes(?:[2-9]|[1-9][0-9]+)?\.dex', name):
                definitions.update(dex_definitions(archive.read(name)))
    missing = REQUIRED - definitions
    if missing:
        raise ValueError('Missing startup definitions: ' + ', '.join(sorted(missing)))
    return definitions


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    args = parser.parse_args()
    try:
        definitions = verify(args.apk)
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        parser.exit(1, f'FAIL: {error}\n')
    print(f'PASS: all {len(REQUIRED)} startup classes defined in final APK ({len(definitions)} total classes).')
