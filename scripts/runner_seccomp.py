"""Small Linux x86-64 seccomp filter: keep descendants observable and namespaces fixed.

Networking is enforced separately by the network namespace. This filter closes
ptrace/CLONE_UNTRACED/io_uring audit bypasses. Unsupported architectures fail closed.
"""
import platform
import struct


def program():
    if platform.machine().lower() not in ("x86_64", "amd64"):
        raise RuntimeError("ISOLATION_UNAVAILABLE")
    instructions = []
    def emit(code, jt=0, jf=0, k=0):
        instructions.append(struct.pack("HBBI", code, jt, jf, k))
    load, equal, bitset, ret = 0x20, 0x15, 0x45, 0x06
    allow, kill, errno = 0x7fff0000, 0x80000000, 0x00050000
    emit(load, k=4)  # seccomp_data.arch
    emit(equal, jt=1, k=0xc000003e)  # AUDIT_ARCH_X86_64
    emit(ret, k=kill)
    emit(load, k=0)  # syscall number
    emit(bitset, jf=1, k=0x40000000)  # x32 ABI
    emit(ret, k=kill)
    emit(equal, jf=1, k=435)  # clone3: glibc safely falls back to clone
    emit(ret, k=errno | 38)  # ENOSYS
    emit(equal, jf=3, k=56)  # clone
    emit(load, k=16)  # flags, low 32 bits of argument 0
    emit(bitset, jf=1, k=0x00800000)  # CLONE_UNTRACED
    emit(ret, k=errno | 1)
    emit(load, k=0)
    for syscall in (101, 155, 165, 166, 272, 298, 308, 321, 323, 425, 426, 427, 428, 429, 430, 431, 432, 433, 442):
        emit(equal, jf=1, k=syscall)
        emit(ret, k=errno | 1)
    emit(ret, k=allow)
    return b"".join(instructions)
