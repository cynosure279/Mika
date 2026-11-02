// 页面只通过宿主提供的 mika 白名单桥工作，不直接访问 Android 接口。
(function () {
  "use strict";

  var seq = 0;
  var pending = new Map();

  function call(method, params) {
    return new Promise(function (resolve, reject) {
      if (!window.mika || typeof window.mika.postMessage !== "function") {
        reject(new Error("mika bridge unavailable"));
        return;
      }
      var id = String(++seq);
      pending.set(id, { resolve: resolve, reject: reject });
      window.mika.postMessage(JSON.stringify({ id: id, method: method, params: params || {} }));
    });
  }

  if (window.mika) {
    window.mika.onmessage = function (event) {
      var reply;
      try {
        reply = JSON.parse(event.data);
      } catch (e) {
        return;
      }
      var waiter = pending.get(String(reply.id));
      if (!waiter) return;
      pending.delete(String(reply.id));
      if (reply.ok) waiter.resolve(reply.result);
      else waiter.reject(new Error(reply.error + ": " + (reply.message || "")));
    };
  }

  var webhook = document.getElementById("webhook");
  var secret = document.getElementById("secret");
  var status = document.getElementById("status");

  function setStatus(text) {
    status.textContent = text || "";
  }

  async function load() {
    try {
      var w = await call("settings.get", { key: "webhook" });
      var s = await call("settings.get", { key: "secret" });
      webhook.value = typeof w === "string" ? w : "";
      secret.value = typeof s === "string" ? s : "";
    } catch (e) {
      setStatus("读取设置失败：" + e.message);
    }
  }

  document.getElementById("save").addEventListener("click", async function () {
    try {
      await call("settings.set", { key: "webhook", value: webhook.value.trim() });
      await call("settings.set", { key: "secret", value: secret.value.trim() });
      setStatus("已保存");
    } catch (e) {
      setStatus("保存失败：" + e.message);
    }
  });

  document.getElementById("close").addEventListener("click", function () {
    call("close").catch(function () {});
  });

  load();
})();
