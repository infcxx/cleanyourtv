# Sony XR-75X90J — debloat list and the Xiaomi Bluetooth remote

Measured on the real set over ADB: `ro.product.model` = `BRAVIA 4K VH2`,
`ro.build.display.id` = `BRAVIA_VH2_M_CN-user 12 STT2.230505.001.S136 682241 release-keys`,
SDK 31. 233 packages, 27 of them user-installed.

## What is disabled

`debloat.txt` is the full, commented list (33 packages). `debloat-phase1.txt` is the same list minus
the stock launcher, for use before the new launcher is confirmed as Home; `build-and-install.sh`
handles that ordering itself.

```bash
TV_HOST=192.168.31.94 tools/disable.sh examples/sony-xr75x90j/debloat.txt
TV_HOST=192.168.31.94 tools/restore.sh  examples/sony-xr75x90j/debloat.txt
```

The groups are: Sony telemetry/analytics (Device Agent, IDD, LastAccess, InteractiveTvUtil, bug
report), Sony content and ad services (Bravia Show, LivingFit, My Sony, demo/multi-screen apps,
SemcMusic, the Dangbei-backed Sony market), the Dangbei launcher/market/poster apps, unneeded
background services (LeBo cast, second-display bridge, Huey DLNA renderer), the streaming apps the
owner does not use (GITV, NewTV, OUmi, Ali cloud drive), and the firmware updater.

## Three things this TV makes easy to get wrong

**`com.sony.dtv.demosystemsupport` is not demo bloat.** Despite the name it owns
`com.sony.dtv.demosystemsupport.action.TOGGLE_SUBWOOFER` over HDMI-CEC, i.e. it is the receiver for
the subwoofer/CEC toggle. It is deliberately **not** in the list; disabling it breaks that feature.

**The MediaTek agents stay on.** `com.mediatek.agent`, `com.mediatek.dmagent`,
`com.mediatek.backgrounddetection`, `com.mediatek.network` and `mtktvapi.agent` are PERSISTENT
platform services, none holds `android.permission.INTERNET` (so they cannot phone home anyway), and
`com.mediatek.agent` declares `TVRemoteServiceReceiver` whose `BIND_TVREMOTE_SERVICE_AGENT`
permission is held by `com.sony.dtv.tvapi`. Breaking remote-control plumbing is out of scope here.

**Netflix can be a ghost package.** On this set `pm list packages` did not list `com.netflix.ninja`
at all, `pm path` returned nothing and dumpsys logged `Deleting invalid package at
/data/app/com.netflix.ninja-...`. The stale record registers no launcher activity, so it can neither
be shown in the dock nor launched. It is not in the debloat list because there is nothing to disable.
Reinstalling it from the Play Store brings it back.

There is no Google Play Services and no Chromecast on this China firmware (`com.google.android.gms`
is not installed), so those questions do not arise.

## The stock launcher is Dangbei, not Google's

`com.dangbei.TVHomeLauncher` ships as `/system/system_ext/priv-app/ChinaHome`. Pass it to the
installer and the restore script:

```bash
STOCK_LAUNCHER=com.dangbei.TVHomeLauncher TV_HOST=<ip> launcher/build-and-install.sh
TV_HOST=<ip> tools/restore.sh examples/sony-xr75x90j/debloat.txt com.dangbei.TVHomeLauncher
```

## Pairing the Xiaomi Bluetooth Voice Remote (小米蓝牙语音遥控器)

This works and is reversible; re-pairing takes about a minute.

**Pairing itself.** The remote is a standard BLE HID device: it advertises service UUID `0x1812`
with the name `小米蓝牙语音遥控器`, vendor `0x2717` product `0x32b8`. Two things get in the way:

1. Sony's *Add accessory* screen uses classic Bluetooth discovery, which on this firmware finds
   **nothing at all** - it misses even nearby phones. The remote never shows up there.
2. It only advertises while it is in pairing mode, and that window is 30-60 seconds.

So pair it with a plain BLE scan instead, which does work. Any BLE scanner will do; the one used
here was a throwaway 11 KB app that called `BluetoothAdapter.getBluetoothLeScanner()`, matched the
`0x1812` service UUID and called `createBond()` on the matching device. Hold the remote's **Home**
button until its LED blinks fast, start the scan within that window, and it pairs:

```
Bonded devices:
    E8:55:40:E1:FC:28 [  LE  ] 小米蓝牙语音遥控器
mInputDevices:
    E8:55:40:E1:FC:28 : 2
HID_HOST connection policy changed: : -1 -> 100
```

Voice, standby wake-up, the D-pad, OK, Back and volume all work out of the box. If you would rather
not use a scanner app, a Sony set that already knows the remote reconnects on its own; this section
is for pairing from scratch.

**Pairing can fail with reason 9, and it is not the remote's fault.** Every attempt ended after
exactly 30 s with `reason: 9` (`REMOTE_DEVICE_DOWN`) and `btm_sec_pairing_timeout`. The log held the
real cause:

```
W bluetooth: connection_manager.cc:225 direct_connect_add:
    Background connection attempt already in progress app_id=2a
apps doing background connect: 4
```

This firmware keeps several stale GATT background connections open to the remote. They collide with
the SMP handshake, so bonding never completes. Cycling Bluetooth tears those clients down, and
pairing then succeeds before anything re-registers. That is what the `RESET BT + PAIR` button in the
pairing app does: adapter off, 2.5 s, adapter on, 2.5 s, then scan. If you pair from your own tool,
do the same cycle first.

**The two dead keys.** Home and the unlabelled "TV" key do nothing once a third-party launcher is
installed. `getevent` shows what the remote actually sends:

| Key | HID usage | Linux key | Generic.kl maps to | Why it was dead |
|---|---|---|---|---|
| Home | `0x4a` | `KEY_HOME` (102) | `MOVE_HOME` | only a launcher answers that code |
| TV | `0x35` | `KEY_GRAVE` (41) | `GRAVE` | Sony's `GlobalKeyManager` swallows it |
| Menu | `0x65` | `KEY_COMPOSE` (127) | `MENU` | works already; apps handle it |

The key layout cannot be fixed: `/system` and `/vendor` are read-only, there is no root, and a key
layout shipped inside an APK is not picked up by this firmware (tried). Enable the launcher's
service instead:

```bash
MI_REMOTE_SERVICE=1 TV_HOST=<ip> launcher/build-and-install.sh
```

It gives Home back (global action HOME, identical to the real key) and makes the TV key switch to
the connected HDMI input - or go home, or open the input picker, depending on where you are. It
survives reboots, but **Android switches accessibility services off when the launcher is
reinstalled**, so pass `MI_REMOTE_SERVICE=1` every time you update.

Note: ADB cannot verify the TV key. `input keyevent 41` is swallowed by Sony's global-key handling
before it reaches anything, so test it by pressing the real key.

## Undo

```bash
TV_HOST=<ip> tools/restore.sh examples/sony-xr75x90j/debloat.txt [com.dangbei.TVHomeLauncher]
```

Verified on the device: it re-enabled all 33 packages, restored `com.dangbei.TVHomeLauncher` as the
default Home, disabled the new launcher and reset the animation scales.
