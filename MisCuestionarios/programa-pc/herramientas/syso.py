"""Genera un .syso (objeto COFF con recursos) para que Go meta icono, manifiesto y versión en el .exe.
Uso: python syso.py icono.ico salida.syso "Descripción" 1.0.0.0"""
import struct, sys

def ico_images(path):
    d = open(path, 'rb').read()
    _, typ, n = struct.unpack_from('<HHH', d, 0)
    out = []
    for i in range(n):
        w, h, cc, r, planes, bpp, size, off = struct.unpack_from('<BBBBHHII', d, 6 + 16 * i)
        out.append((w, h, cc, planes, bpp, d[off:off + size]))
    return out

def manifest():
    return b'''<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<assembly xmlns="urn:schemas-microsoft-com:asm.v1" manifestVersion="1.0">
  <assemblyIdentity type="win32" name="MyQuizzes" version="1.0.0.0" processorArchitecture="*"/>
  <dependency><dependentAssembly><assemblyIdentity type="win32" name="Microsoft.Windows.Common-Controls" version="6.0.0.0" processorArchitecture="*" publicKeyToken="6595b64144ccf1df" language="*"/></dependentAssembly></dependency>
  <trustInfo xmlns="urn:schemas-microsoft-com:asm.v3"><security><requestedPrivileges><requestedExecutionLevel level="asInvoker" uiAccess="false"/></requestedPrivileges></security></trustInfo>
  <compatibility xmlns="urn:schemas-microsoft-com:compatibility.v1"><application><supportedOS Id="{8e0f7a12-bfb3-4fe8-b9a5-48fd50a15a9a}"/></application></compatibility>
  <application xmlns="urn:schemas-microsoft-com:asm.v3"><windowsSettings>
    <dpiAware xmlns="http://schemas.microsoft.com/SMI/2005/WindowsSettings">true/pm</dpiAware>
    <dpiAwareness xmlns="http://schemas.microsoft.com/SMI/2016/WindowsSettings">PerMonitorV2</dpiAwareness>
  </windowsSettings></application>
</assembly>
'''

def versioninfo(desc, ver, fname):
    nums = [int(x) for x in ver.split('.')] + [0, 0, 0, 0]
    ms, ls = (nums[0] << 16) | nums[1], (nums[2] << 16) | nums[3]
    def u16(s): return s.encode('utf-16-le') + b'\0\0'
    def pad(b): return b + b'\0' * ((4 - len(b) % 4) % 4)
    def block(key, value=b'', vtype=0, children=b'', vlen=None):
        hdr_key = pad(struct.pack('<HHH', 0, 0, 0) + u16(key))
        body = hdr_key + pad(value) + children
        L = len(body)
        vl = vlen if vlen is not None else (len(value) // 2 if vtype == 1 else len(value))
        return pad(struct.pack('<HHH', L, vl, vtype) + body[6:])
    def sval(k, v):
        val = u16(v)
        return block(k, val, 1, vlen=len(val) // 2)
    strings = b''.join(sval(k, v) for k, v in [
        ('CompanyName', 'MyQuizzes'), ('FileDescription', desc), ('FileVersion', ver), ('InternalName', fname),
        ('LegalCopyright', '© MyQuizzes'), ('OriginalFilename', fname), ('ProductName', 'MyQuizzes'), ('ProductVersion', ver)])
    st = block('040904B0', children=strings)
    sfi = block('StringFileInfo', children=st)
    var = block('Translation', struct.pack('<HH', 0x0409, 0x04B0), 0)
    vfi = block('VarFileInfo', children=var)
    ffi = struct.pack('<13I', 0xFEEF04BD, 0x00010000, ms, ls, ms, ls, 0x3F, 0, 0x40004, 1, 0, 0, 0)
    return block('VS_VERSION_INFO', ffi, 0, children=sfi + vfi)

def build(ico, out, desc, ver, fname):
    imgs = ico_images(ico)
    res = {}   # type -> [(id, data)]
    res[3] = [(i + 1, im[5]) for i, im in enumerate(imgs)]
    grp = struct.pack('<HHH', 0, 1, len(imgs)) + b''.join(
        struct.pack('<BBBBHHIH', w, h, cc, 0, planes or 1, bpp or 32, len(data), i + 1) for i, (w, h, cc, planes, bpp, data) in enumerate(imgs))
    res[14] = [(1, grp)]
    res[16] = [(1, versioninfo(desc, ver, fname))]
    res[24] = [(1, manifest())]
    types = sorted(res)
    # tamaños de la estructura de directorios
    def dirsize(n): return 16 + 8 * n
    off = dirsize(len(types))
    l2 = {}
    for t in types:
        l2[t] = off; off += dirsize(len(res[t]))
    l3 = {}
    for t in types:
        for (i, _) in res[t]:
            l3[(t, i)] = off; off += dirsize(1)
    de = {}
    for t in types:
        for (i, _) in res[t]:
            de[(t, i)] = off; off += 16
    data_off = {}
    for t in types:
        for (i, d) in res[t]:
            off = (off + 7) & ~7; data_off[(t, i)] = off; off += len(d)
    raw = bytearray(off)
    def wdir(at, entries):
        struct.pack_into('<IIHHHH', raw, at, 0, 0, 0, 0, 0, len(entries))
        for k, (name, target) in enumerate(entries):
            struct.pack_into('<II', raw, at + 16 + 8 * k, name, target)
    wdir(0, [(t, 0x80000000 | l2[t]) for t in types])
    relocs = []
    for t in types:
        wdir(l2[t], [(i, 0x80000000 | l3[(t, i)]) for (i, _) in res[t]])
        for (i, d) in res[t]:
            wdir(l3[(t, i)], [(0x0409, de[(t, i)])])
            struct.pack_into('<IIII', raw, de[(t, i)], data_off[(t, i)], len(d), 0, 0)
            relocs.append(de[(t, i)])
            raw[data_off[(t, i)]:data_off[(t, i)] + len(d)] = d
    # COFF
    hdr_size, sec_size = 20, 40
    raw_ptr = hdr_size + sec_size
    rel_ptr = raw_ptr + len(raw)
    sym_ptr = rel_ptr + 10 * len(relocs)
    coff = struct.pack('<HHIIIHH', 0x8664, 1, 0, sym_ptr, 1, 0, 0)
    sec = struct.pack('<8sIIIIIIHHI', b'.rsrc', 0, 0, len(raw), raw_ptr, rel_ptr, 0, len(relocs), 0, 0x40000040)
    rel = b''.join(struct.pack('<IIH', r, 0, 3) for r in relocs)   # IMAGE_REL_AMD64_ADDR32NB contra el símbolo 0 (.rsrc)
    sym = struct.pack('<8sIhHBB', b'.rsrc', 0, 1, 0, 3, 0)
    strtab = struct.pack('<I', 4)
    open(out, 'wb').write(coff + sec + bytes(raw) + rel + sym + strtab)

if __name__ == '__main__':
    build(sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[5] if len(sys.argv) > 5 else 'myquizzes.exe')
    print('ok', sys.argv[2])
