# APRS Tracking Contract

สถานะเอกสาร: canonical hand-off สำหรับ APRS tracking ของ Minimum (ตรวจทาน 2026-08-08)

เอกสารนี้เป็นสัญญาระหว่างโค้ด, provisioning และการตรวจสอบ APRS.fi ส่วนที่เกี่ยวกับตำแหน่งต้อง
ยึดเอกสารนี้ร่วมกับ `ARCHITECTURE.md`, `CONFIG_BACKEND.md` และ `TEST_MATRIX.md` ก่อนแก้ไข
อย่าเปลี่ยนชนิด packet หรือ identity เพียงเพราะ APRS.fi แสดง source callsign ใน protocol header

## ขอบเขตและ hardware gate

- Tracking เปิดได้เฉพาะ `RadioDeviceProfile.T56` ที่ตรวจจาก hardware จริง
- T99 และ generic Android ถูกปฏิเสธที่ immutable capability gate ไม่ว่าคอนฟิกระยะไกลจะเขียน
  `tracking.enabled=true` หรือไม่
- Tracking ปิดโดยปริยายเมื่อไม่มี `tracking.aprs.enabled` หรือเมื่อปิด tracking; การปิดจะล้าง
  last-success cache ของตำแหน่ง
- PTT ใช้เฉพาะ fix ที่ผ่านการยอมรับและเก็บอยู่ใน coordinator แล้ว ไม่รอหรือเริ่ม GPS ใหม่จาก
  การกด PTT

## Public identity: APRS Object

ตำแหน่งต้องเป็น APRS Object (`;`) ไม่ใช่ station position ของ callsign (`@`). Object name มี
ความยาวคงที่ 9 bytes ตาม APRS และเลือกตามลำดับนี้:

1. ถ้ามี `tracking.aprs.objectName` ให้ normalize ค่านั้นเป็นตัวพิมพ์ใหญ่และเติมช่องว่างด้านขวา
   ให้ครบ 9 bytes
2. ถ้าไม่ระบุ ให้ derive จาก Device ID เป็น `VR-` ตามด้วย Device ID หกตัว

ตัวอย่าง fallback:

```text
Device ID: ABC123
Object:    VR-ABC123
```

คำนำหน้า `VR-` ตั้งใจให้ค้นด้วย wildcard `VR-*` บน APRS.fi ได้ง่าย ตัวอักษรที่เกิน/ผิดรูปแบบ
จะผ่าน defensive fallback ที่ยังคง prefix และจำกัดความยาว 9 ตัว เครื่องหมาย `*` หลังชื่อ Object
หมายถึง live object; source callsign ยังคงอยู่ใน header เพื่อ authentication/routing เท่านั้น
และไม่ใช่ identity ของตำแหน่งที่ผู้ใช้ค้นหา

ตัวอย่างการกำหนดชื่อเอง:

```json
{
  "tracking": {
    "aprs": {
      "objectName": "T56-ROOF"
    }
  }
}
```

ชื่อที่กำหนดเองยาว 1-9 ตัวอักษร ASCII, ต้องเริ่ม/จบด้วยตัวอักษรหรือตัวเลข (ท้ายเป็น `_` หรือ `-`
ได้) และตรงกลางใช้ได้เฉพาะตัวอักษร, ตัวเลข, space, `_` หรือ `-` เท่านั้น ค่าว่าง, whitespace ที่
หัว/ท้าย, Unicode, `/`, `;`, CR/LF และชื่อยาวเกิน
9 ตัวถูกปฏิเสธทั้งใน schema และ Android parser การไม่ใส่ field ไม่ใช่ error และคง fallback เดิม

เมื่อชื่อเปลี่ยน coordinator จะล้าง duplicate/in-flight state ของ identity เก่า เพื่อให้ accepted
fix ถัดไปสร้าง Object ใหม่ การเปลี่ยนค่าจริงต้องเพิ่ม `configVersion` ตามกติกา Last Known Good
APRS-IS/APRS.fi ไม่ลบชื่อเก่าจาก history อัตโนมัติ และ implementation ปัจจุบันยังไม่ส่ง Object
kill packet สำหรับชื่อเดิม

T56 ที่ provision แล้วสามารถแก้เฉพาะ field นี้โดยไม่ export private config ด้วย
`scripts/set-t56-aprs-object-name.ps1`; protected receiver จะ validate ชื่อ, เพิ่ม `configVersion`
และรักษา credentials/connection/channel เดิมไว้ใน app-private storage

ตัวอย่างที่ทำให้เห็นโครงสร้าง (ค่าทั้งหมดเป็นตัวอย่างสมมติ):

```text
N0CALL>APRS,TCPIP*:;VR-ABC123*081739z1400.00N/10000.00E-T56 ST A8 B87+ T32.4 W-61 4G-96 S12G
```

ใน runtime packet จะถูกสร้างโดย `AprsPacketEncoder.encodeObject(...)` และส่งจาก
`AprsTrackingManager`; ห้ามเปลี่ยนกลับไป `encodePosition(...)` สำหรับ tracking

## State symbols

ใช้ primary APRS symbol table `/` และเปลี่ยน symbol code ให้ตรง movement state:

| State | Code | ความหมายที่แสดงบนแผนที่ |
| --- | --- | --- |
| `STATIONARY` | `-` | house/home |
| `WALKING` | `[` | person/jogger |
| `VEHICLE` | `>` | car |

การเปลี่ยน state เป็น protocol behavior ไม่ใช่แค่ UI decoration เพราะมีผลต่อ icon ของ Object
และ trigger ที่ coordinator อนุญาต

## Health comment contract

Health ถูกแนบต่อท้าย position Object beacon เดิม ไม่มี packet แยก เพื่อไม่สร้าง packet storm และ
ให้ตำแหน่งกับสถานะอุปกรณ์มี timestamp เดียวกัน รูปแบบ ASCII ถูกจำกัดความยาว/อักขระโดย encoder

| Field | รูปแบบ | หมายเหตุ |
| --- | --- | --- |
| state | `T56 ST`, `T56 WA`, `T56 VE` | stationary/walking/vehicle |
| GPS accuracy | `A<metres>` | ปัดเป็นเมตรจาก fix ที่ส่ง; ขาดได้ |
| battery | `B<percent>` | 0-100; เครื่องหมาย `+` ต่อท้ายเมื่อ charging/full |
| battery temperature | `T<degC>` | ทศนิยมหนึ่งตำแหน่งจาก Android tenths of degree C |
| Wi-Fi RSSI | `W<dBm>` | มีเมื่อ Android รายงานค่า |
| mobile type/RSSI | `4G-96`, `3GNA`, ฯลฯ | ใช้ `MNA` เมื่อไม่มีประเภท; `NA` เมื่อมีประเภทแต่ไม่มี RSSI |
| free app storage | `S<n>G` | ปัดลงเป็น GiB ของ app volume |

ไม่ส่ง SSID, IMEI, เบอร์โทรศัพท์, serial, Device ID, credentials, token หรือพิกัดซ้ำใน comment
ค่า radio/health ที่อ่านไม่ได้ใช้ `NA` แทนการเดาค่า

## Transport and receipt

Production path คือ authenticated HTTPS POST ไปยัง server-advertised APRS-IS send-only endpoint
(ค่า default ในโค้ด `ametx.com:8888`). ใช้ Basic auth จาก source callsign/passcode ผ่าน TLS และ
API-22 ใช้ pinned ISRG Root X1 CA พร้อม hostname verification ปกติ; ห้ามใช้ trust-all หรือปิด
certificate verification

ถือว่าส่งสำเร็จเฉพาะ HTTP `204 No Content` และ response header `X-Packetsrcvd` เป็นจำนวนที่ parse
ได้มากกว่า 0 เท่านั้น จึงจะ persist last successful fix และ log `APRS position accepted by
send-only server` การเขียนสำเร็จก่อน receipt เป็น `UNCERTAIN_DELIVERY` และต้องใช้ backoff ระยะยาว
เพื่อหลีกเลี่ยง duplicate

TCP transport ใน source tree เป็น legacy path และไม่มี server receipt; อย่าใช้เป็นหลักฐาน live
acceptance หรือเปิดเผย passcode ผ่าน plaintext endpoint โดยไม่ผ่านการทบทวนแยกต่างหาก

## GPS, cadence and retry policy

- Fix ต้องมีพิกัดถูกต้อง, accuracy > 0 และไม่เกิน 100 m, อายุไม่เกิน 2 นาที
- Stationary: regular GPS request ใน acquisition window 90 วินาที แล้วหยุด; heartbeat/poll ทุก
  30 นาที
- Walking: GPS sample ประมาณ 20 วินาที/15 m และ beacon interval 2 นาที
- Vehicle: sample ประมาณ 8 วินาที/20 m; beacon interval 60/90/120 วินาทีตามความเร็ว
- Turn beacon ต้องห่างอย่างน้อย 60 วินาที; PTT beacon อย่างน้อย 2 นาที และตำแหน่งเดิมซ้ำได้ไม่
  บ่อยกว่า 10 นาที
- Coordinator เก็บ in-flight หนึ่งรายการและ pending ใหม่ที่สุดหนึ่งรายการ จึงไม่สะสมประวัติหรือ
  ส่ง packet รัวเมื่อ GPS callback/connection retry ซ้อนกัน
- ความล้มเหลวก่อนเขียน body retry ได้ (เริ่ม 60 วินาที); หลังเขียนแล้วแต่ receipt ไม่ชัดเจนให้
  backoff 15 นาทีขึ้นไป; auth/config rejection เป็น permanent จนกว่าจะเปลี่ยน config

`requestSingleUpdate()` เคยใช้แล้วไม่ส่ง callback ที่ T56 จึงถูกถอดออก ห้ามนำกลับมาโดยไม่มี
device test ที่พิสูจน์ callback จริง

## Migration history and APRS.fi behavior

1. รุ่นแรกใช้ Object format แบบไม่มี `VR-`
2. มีการทดลองชั่วคราวส่ง callsign-owned station position (`@`) ซึ่งผู้ใช้ปฏิเสธ
3. รุ่นปัจจุบัน restore Object (`;`) และเพิ่ม `VR-` prefix เป็น fallback; packet format migration
   version คือ 5
4. เพิ่ม optional `tracking.aprs.objectName` โดยไม่เพิ่ม schema version เพราะ backward compatible;
   config ที่ไม่กำหนด field ยังคงได้ `VR-<DeviceID>`
5. APRS.fi/APRS-IS ไม่สามารถลบ packet เก่าที่ถูกส่งไปแล้วได้ ดังนั้น history อาจยังมี callsign
   position รุ่นทดลองอยู่ ให้ตรวจเฉพาะ Object ใหม่เป็น source of truth

## Verification checklist

สร้างและทดสอบจาก junction ที่ไม่มีช่องว่างใน path:

```powershell
Set-Location D:\mumla-dev
.\gradlew.bat :app:testFossDebugUnitTest :app:assembleFossDebug --no-daemon
adb -P <port> -s <t56> install -r app\build\outputs\apk\foss\debug\mumla-foss-debug.apk
adb -P <port> -s <t56> logcat -c
```

เปิด tracking บน T56 กลางแจ้ง รอ GPS fix แล้วตรวจ log tag `MinimumAprs` ให้เห็น
`APRS position accepted by send-only server` จากนั้นตรวจ APRS.fi ด้วย wildcard `VR-*` และยืนยัน
ว่าเป็น Object พร้อม symbol/Health comment ที่คาดไว้ ตรวจ T99 แยกต่างหากว่ามี tracking manager,
location request และ APRS activity เป็นศูนย์

ห้ามคัดลอก passcode, token, serial, Device ID หรือพิกัดจริงลงใน issue, commit, log ที่แชร์ หรือ
ตัวอย่างเอกสาร; ใช้ค่าปลอม/ปัดความละเอียดเมื่อจำเป็นต้องแสดงรูปแบบ packet

## Open risks

- A-GPS/SUPL/XTRA contribution บน T56 ยังไม่ได้พิสูจน์แยกจาก standalone GPS
- APRS endpoint/server policy อาจเปลี่ยน ต้องยืนยัน HTTPS send-only port และ receipt contract ก่อน
  เปลี่ยน host/port
- APRS transport ยังต้องมี integration test สำหรับ certificate rotation และ server-side indexing
- การเผยแพร่พิกัดเป็นข้อมูลสาธารณะ ต้องเปิด tracking เฉพาะอุปกรณ์/ผู้ใช้ที่ได้รับอนุญาต
