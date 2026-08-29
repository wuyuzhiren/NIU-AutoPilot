#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Parse Android btsnoop_hci.log, extract BLE ATT writes (commands sent to the scooter)
and map GATT handles -> characteristic UUIDs. Output focused on the NIU write characteristic."""
import struct, sys, os

def parse_btsnoop(path):
    with open(path, 'rb') as f:
        header = f.read(16)
        if header[:8] != b'btsnoop\x00':
            print('NOT a btsnoop file')
            return []
        pkts = []
        while True:
            rec = f.read(24)
            if len(rec) < 24:
                break
            orig_len, incl_len, flags, drops, ts = struct.unpack('>IIIIq', rec)
            data = f.read(incl_len)
            if len(data) < incl_len:
                break
            pkts.append((ts, flags, data))
        return pkts

def parse_hci(pkt):
    """Return (type, payload) or (None,None)."""
    if len(pkt) < 1:
        return None, None
    h4 = pkt[0]
    if h4 == 0x02:  # ACL Data
        if len(pkt) < 5:
            return None, None
        handle_flags = struct.unpack('<H', pkt[1:3])[0]
        length = struct.unpack('<H', pkt[3:5])[0]
        acl = pkt[5:5+length]
        return 'ACL', (handle_flags & 0xFFF, acl)
    return None, None

def parse_l2cap(acl):
    """Return (cid, payload) from ACL payload."""
    if len(acl) < 4:
        return None, None
    l2_len = struct.unpack('<H', acl[0:2])[0]
    cid = struct.unpack('<H', acl[2:4])[0]
    payload = acl[4:4+l2_len]
    return cid, payload

ATT_OP = {
    0x01:'Error Response',0x02:'Exchange MTU Req',0x03:'Exchange MTU Rsp',
    0x04:'Find Information Req',0x05:'Find Information Rsp',0x06:'Find By Type Req',
    0x07:'Find By Type Rsp',0x08:'Read By Type Req',0x09:'Read By Type Rsp',
    0x0A:'Read Req',0x0B:'Read Rsp',0x0C:'Read Blob Req',0x0D:'Read Blob Rsp',
    0x0E:'Read Multiple Req',0x0F:'Read Multiple Rsp',0x10:'Read By Group Type Req',
    0x11:'Read By Group Type Rsp',0x12:'Write Req',0x13:'Write Rsp',
    0x14:'Prepare Write Req',0x15:'Prepare Write Rsp',0x16:'Execute Write Req',
    0x17:'Execute Write Rsp',0x18:'Handle Value Ntf',0x19:'Handle Value Ind',
    0x1B:'Signed Write Cmd',0x52:'Write Cmd',
}

def run(path):
    pkts = parse_btsnoop(path)
    print(f'parsed {len(pkts)} packets from {path}')
    handle_uuid = {}   # handle -> uuid hex
    writes = []        # (ts, direction, handle, value_hex)
    for ts, flags, data in pkts:
        h4_type, acl = parse_hci(data)
        if h4_type != 'ACL' or not acl:
            continue
        cid, att = parse_l2cap(acl[1])
        if cid != 0x0004 or att is None or len(att) < 1:  # ATT channel
            continue
        op = att[0]
        # direction: bit0 of flags: 0=host->controller(from phone), 1=controller->host(to phone)
        direction = (flags & 1)
        dir_str = '手机->车' if direction == 0 else '车->手机'
        if op in (0x12, 0x52):  # Write Request / Write Command -> these are commands to scooter
            if len(att) >= 4:
                handle = struct.unpack('<H', att[1:3])[0]
                value = att[3:]
                writes.append((ts, dir_str, handle, value.hex()))
        elif op == 0x09:  # Read By Type Response: maps handles to UUIDs
            if len(att) >= 2:
                length = att[1]
                body = att[2:]
                # entries: handle(2) + uuid(len-2)
                step = length
                for i in range(0, len(body) - step + 1, step):
                    h = struct.unpack('<H', body[i:i+2])[0]
                    uuid = body[i+2:i+step]
                    if len(uuid) == 2:
                        u = '0000%04x-0000-1000-8000-00805f9b34fb' % struct.unpack('<H', uuid)[0]
                    elif len(uuid) == 16:
                        u = str(bytes(uuid)).upper()
                        import binascii
                        u = binascii.hexlify(uuid).decode()
                        u = '%s-%s-%s-%s-%s' % (u[0:8],u[8:12],u[12:16],u[16:20],u[20:32])
                    else:
                        continue
                    handle_uuid[h] = u
    print('\n=== GATT 特征值映射 (handle->UUID) ===')
    for h, u in sorted(handle_uuid.items()):
        mark = ' <== NIU写特征' if '8ec94e32' in u else (' <== NIU读特征' if '8ec94e31' in u else (' <== NIU服务' if '8ec94e30' in u else ''))
        print(f'  handle 0x{h:04X} -> {u}{mark}')
    print('\n=== 手机发给车辆的写指令 (Write Req / Write Cmd) ===')
    for ts, d, h, v in writes:
        u = handle_uuid.get(h, '未知')
        mark = ' <== NIU写通道' if '8ec94e32' in u else ''
        print(f'  [{d}] handle=0x{h:04X} {u}{mark} value={v}')
    print('\n统计: 共 %d 条写指令' % len(writes))
    # summary by value
    from collections import Counter
    c = Counter((d, h, v) for _,d,h,v in writes)
    print('\n=== 去重汇总 ===')
    for (d,h,v), n in c.items():
        u = handle_uuid.get(h, '未知')
        print(f'  x{n} [{d}] handle=0x{h:04X} {u} value={v}')

if __name__ == '__main__':
    run(sys.argv[1])
