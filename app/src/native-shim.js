/*
 * Bridges the OBD Scanner page to the native ObdLink plugin when it runs inside the Android app.
 *
 * Android's WebView has no Web Bluetooth or Web Serial, so this script provides the small part of
 * navigator.bluetooth / navigator.serial / navigator.wakeLock that the page uses, backed by native
 * code. In an ordinary browser it does nothing. It must load before the page's main script.
 */
(function () {
  var cap = window.Capacitor;
  if (!cap || !cap.isNativePlatform || !cap.isNativePlatform()) return;

  var PLUGIN = 'ObdLink';
  var SPP = '00001101-0000-1000-8000-00805f9b34fb';

  function native(method, options) {
    return cap.nativePromise(PLUGIN, method, options || {}).catch(function (e) {
      throw new Error((e && (e.message || e.errorMessage)) || String(e));
    });
  }
  function toBytes(str) {
    var a = new Uint8Array(str.length);
    for (var i = 0; i < str.length; i++) a[i] = str.charCodeAt(i) & 255;
    return a;
  }
  function toStr(buf) {
    var b = buf instanceof Uint8Array ? buf : buf.buffer ? new Uint8Array(buf.buffer, buf.byteOffset, buf.byteLength) : new Uint8Array(buf);
    var s = '';
    for (var i = 0; i < b.length; i++) s += String.fromCharCode(b[i]);
    return s;
  }
  function uuid(short) {
    return '0000' + short + '-0000-1000-8000-00805f9b34fb';
  }

  // ---- one native link at a time; events are tagged with the session id that opened it
  var seq = 0;
  var current = null;
  cap.addListener(PLUGIN, 'data', function (e) {
    if (current && e.sid === current.sid) current.onData(e.data);
  });
  cap.addListener(PLUGIN, 'closed', function (e) {
    if (!current || e.sid !== current.sid) return;
    var c = current;
    current = null;
    c.onClosed(e.reason);
  });

  function session(onData, onClosed) {
    var sid = ++seq;
    return {
      connect: function (options) {
        current = { sid: sid, onData: onData, onClosed: onClosed };
        options.sid = sid;
        return native('connect', options).catch(function (e) {
          if (current && current.sid === sid) current = null;
          throw e;
        });
      },
      write: function (data) {
        return native('write', { sid: sid, data: typeof data === 'string' ? data : toStr(data) });
      },
      close: function () {
        if (current && current.sid === sid) current = null;
        return native('disconnect', { sid: sid });
      }
    };
  }

  // ---- navigator.serial → Bluetooth Classic (SPP)
  function Port(dev) {
    this._dev = dev;
    this._s = null;
    this._ctl = null;
    this.readable = null;
    this.writable = null;
  }
  Port.prototype.getInfo = function () {
    return { bluetoothServiceClassId: SPP };
  };
  Port.prototype.open = async function () {
    var self = this;
    var ctl;
    var stream = new ReadableStream({ start: function (c) { ctl = c; } });
    var s = session(
      function (data) { try { ctl.enqueue(toBytes(data)); } catch (e) {} },
      function () {
        try { ctl.close(); } catch (e) {}
        self.readable = null;
      }
    );
    this._s = s;
    this._ctl = ctl;
    await s.connect({ kind: 'classic', address: this._dev.address });
    this.readable = stream;
    this.writable = {
      getWriter: function () {
        return { write: function (b) { return s.write(b); }, releaseLock: function () {} };
      }
    };
  };
  Port.prototype.close = async function () {
    var s = this._s;
    try { this._ctl && this._ctl.close(); } catch (e) {}
    this._s = this._ctl = this.readable = this.writable = null;
    if (s) await s.close();
  };

  // ---- navigator.bluetooth → Bluetooth LE. Native code finds the serial characteristics; the page
  //      sees them as one standard fff0 service with a notify (fff1) and a write (fff2) characteristic.
  class Characteristic extends EventTarget {
    constructor(id, properties) {
      super();
      this.uuid = uuid(id);
      this.properties = properties;
      this.value = null;
    }
    async startNotifications() { return this; }
  }
  class Device extends EventTarget {
    constructor(dev) {
      super();
      var self = this;
      this.id = dev.address;
      this.name = dev.name || '';
      this._s = null;
      this.gatt = {
        device: this,
        connected: false,
        connect: function () { return self._connect(); },
        disconnect: function () { self._disconnect(); }
      };
    }
    async _connect() {
      var self = this;
      var notify = new Characteristic('fff1', { notify: true, indicate: false, write: false, writeWithoutResponse: false });
      var write = new Characteristic('fff2', { notify: false, indicate: false, write: true, writeWithoutResponse: true });
      var s = session(
        function (data) {
          notify.value = new DataView(toBytes(data).buffer);
          notify.dispatchEvent(new Event('characteristicvaluechanged'));
        },
        function () {
          self.gatt.connected = false;
          self.dispatchEvent(new Event('gattserverdisconnected'));
        }
      );
      write.writeValue = write.writeValueWithResponse = write.writeValueWithoutResponse = function (b) { return s.write(b); };
      this._s = s;
      await s.connect({ kind: 'ble', address: this.id });
      this.gatt.connected = true;
      var service = { uuid: uuid('fff0'), getCharacteristics: async function () { return [notify, write]; } };
      return { device: this, connected: true, getPrimaryServices: async function () { return [service]; } };
    }
    _disconnect() {
      var s = this._s;
      this._s = null;
      this.gatt.connected = false;
      if (s) s.close().catch(function () {});
    }
  }

  function define(name, value) {
    try {
      Object.defineProperty(navigator, name, { value: value, configurable: true });
    } catch (e) {
      navigator[name] = value;
    }
  }

  define('bluetooth', {
    getAvailability: async function () {
      try { return !!(await native('isEnabled')).enabled; } catch (e) { return false; }
    },
    requestDevice: async function () {
      return new Device(await native('pick', { kind: 'ble' }));
    }
  });
  define('serial', {
    requestPort: async function () {
      return new Port(await native('pick', { kind: 'classic' }));
    }
  });
  define('wakeLock', {
    request: async function () {
      await native('keepAwake', { on: true });
      return {
        release: function () { return native('keepAwake', { on: false }); },
        addEventListener: function () {}
      };
    }
  });

  // ---- extras the page only uses in the app build
  var WIFI_KEY = 'obd-wifi';
  var WIFI_DEFAULT = '192.168.0.10:35000';
  window.ObdNative = {
    /** Opens a TCP link to a Wi-Fi ELM327. `again` reuses the saved address without asking (reconnects). */
    openWifi: async function (again, onData, onClosed) {
      var addr = WIFI_DEFAULT;
      try { addr = localStorage.getItem(WIFI_KEY) || WIFI_DEFAULT; } catch (e) {}
      if (!again) {
        var typed = window.prompt('Wi‑Fi adapter address. Join the adapter’s Wi‑Fi network in phone Settings first.', addr);
        if (typed === null) throw new Error('User cancelled');
        addr = typed.trim() || WIFI_DEFAULT;
      }
      var m = /^\s*([^\s:]+)(?::(\d{1,5}))?\s*$/.exec(addr);
      if (!m) throw new Error('That does not look like an address. Use something like ' + WIFI_DEFAULT + '.');
      var host = m[1];
      var port = m[2] ? parseInt(m[2], 10) : 35000;
      try { localStorage.setItem(WIFI_KEY, host + ':' + port); } catch (e) {}
      var s = session(onData, onClosed);
      await s.connect({ kind: 'wifi', host: host, port: port });
      return {
        label: 'Wi‑Fi adapter (' + host + ')',
        write: function (str) { return s.write(str); },
        close: function () { return s.close(); }
      };
    }
  };
})();
