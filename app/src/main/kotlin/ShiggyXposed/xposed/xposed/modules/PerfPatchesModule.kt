package ShiggyXposed.xposed.modules

import de.robv.android.xposed.callbacks.XC_LoadPackage
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import ShiggyXposed.xposed.Constants
import ShiggyXposed.xposed.Module
import ShiggyXposed.xposed.Utils.Log
import java.io.File

/**
 * PerfPatchesModule
 *
 * Writes a small JavaScript preload into the module's `preloads` directory.
 * The preload performs guarded, best-effort runtime monkeypatches to:
 *  - add memoization to expensive pure functions (if present)
 *  - cache RowGenerator.generate outputs on instances when inputs look stable
 *  - batch analytics/log events to avoid high-frequency synchronous work
 *
 * The implementation is intentionally defensive: it never throws and only applies
 * patches if the targeted symbols exist.
 */
object PerfPatchesModule : Module() {
    private const val FILE_NAME = "perf_patches.js"

    override fun onLoad(packageParam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Build preloads path inside the app data files directory
            val filesDir = File(packageParam.appInfo.dataDir, Constants.FILES_DIR).apply { mkdirs() }
            val preloadsDir = File(filesDir, HookScriptLoaderModule.PRELOADS_DIR).apply { mkdirs() }
            val out = File(preloadsDir, FILE_NAME)

            // Defensive JS runtime patch. Avoid template literals with ${} to prevent Kotlin interpolation.
            val js = """
                (function(){
                  try {
                    if (!globalThis.__SHIGGY_PERF_PATCHES__) {
                      globalThis.__SHIGGY_PERF_PATCHES__ = { installedAt: Date.now(), enabled: true };
                    }

                    function safe(fn) {
                      return function() {
                        try { return fn.apply(this, arguments); }
                        catch (e) { try { console && console.warn && console.warn('shiggy patch error', e); } catch(_){} }
                      };
                    }

                    function memoize(fn, maxEntries) {
                      if (typeof fn !== 'function') return fn;
                      var cache = new Map();
                      maxEntries = Number(maxEntries) || 128;
                      return function() {
                        var key;
                        try { key = JSON.stringify(Array.prototype.slice.call(arguments)); }
                        catch (e) { key = String(arguments.length) + ':' + String(arguments[0]); }
                        if (cache.has(key)) return cache.get(key);
                        var res = fn.apply(this, arguments);
                        cache.set(key, res);
                        if (cache.size > maxEntries) {
                          var it = cache.keys().next();
                          if (!it.done) cache.delete(it.value);
                        }
                        return res;
                      };
                    }

                    function createBatched(fn, intervalMs) {
                      if (typeof fn !== 'function') return fn;
                      var buf = [];
                      var timer = null;
                      intervalMs = Number(intervalMs) || 2000;
                      function flush() {
                        var batch = buf.splice(0, buf.length);
                        if (!batch.length) return;
                        try { fn.call(null, batch); }
                        catch (e) {
                          // Fallback: call per event asynchronously
                          try {
                            batch.forEach(function(ev){
                              setTimeout(function(){ try { fn(ev); } catch (_) {} }, 0);
                            });
                          } catch (_) {}
                        }
                      }
                      return function(event) {
                        try {
                          buf.push(event);
                          if (!timer) timer = setTimeout(function(){ timer = null; flush(); }, intervalMs);
                        } catch (e) {}
                      };
                    }

                    function patchIfExists(path, wrapper) {
                      try {
                        var parts = path.split('.');
                        var obj = globalThis;
                        for (var i = 0; i < parts.length - 1; i++) {
                          obj = obj && obj[parts[i]];
                        }
                        if (!obj) return false;
                        var key = parts[parts.length - 1];
                        if (typeof obj[key] !== 'function') return false;
                        obj[key] = wrapper(obj[key]);
                        return true;
                      } catch (e) { return false; }
                    }

                    // Try memoizing a few well-known functions
                    ['computeHappeningNowState', 'computeNowState'].forEach(function(name){
                      try { patchIfExists(name, function(orig){ return memoize(orig, 128); }); } catch(_) {}
                    });

                    // Try caching RowGenerator.prototype.generate by instance signature
                    try {
                      var RG = globalThis && globalThis.RowGenerator;
                      if (RG && RG.prototype && typeof RG.prototype.generate === 'function') {
                        (function(){
                          var orig = RG.prototype.generate;
                          RG.prototype.generate = function() {
                            try {
                              var sig;
                              if (arguments && arguments.length) {
                                var a0 = arguments[0];
                                if (Array.isArray(a0)) {
                                  sig = 'arr:' + a0.length + ':' + a0.slice(0,8).map(function(x){ return x && x.id ? x.id : ''; }).join(',');
                                } else if (a0 && a0.id) {
                                  sig = 'id:' + a0.id;
                                } else {
                                  try { sig = JSON.stringify(a0).slice(0,200); } catch(e){ sig = String(a0); }
                                }
                              } else sig = 'noargs';

                              if (this.__shiggy_sig === sig && typeof this.__shiggy_cache !== 'undefined') {
                                return this.__shiggy_cache;
                              }
                              var res = orig.apply(this, arguments);
                              this.__shiggy_sig = sig;
                              this.__shiggy_cache = res;
                              return res;
                            } catch (e) { return orig.apply(this, arguments); }
                          };
                        })();
                      }
                    } catch (e) {}

                    // Batch analytics-like methods (best-effort)
                    ['app_ui_viewed', 'logEvent', 'Analytics.log', 'Analytics.track', 'analytics.trackEvent'].forEach(function(name){
                      try {
                        var parts = name.split('.');
                        var obj = globalThis;
                        for (var i = 0; i < parts.length - 1; i++) obj = obj && obj[parts[i]];
                        if (!obj) return;
                        var key = parts[parts.length - 1];
                        if (typeof obj[key] === 'function') obj[key] = createBatched(obj[key], 2000);
                      } catch (e) {}
                    });

                    // Expose lightweight API
                    try {
                      if (!globalThis.__SHIGGY_PERF_API__) {
                        globalThis.__SHIGGY_PERF_API__ = {
                          installedAt: Date.now(),
                          enabled: true,
                          uninstall: function() { try { globalThis.__SHIGGY_PERF_API__.enabled = false; } catch(e) {} }
                        };
                      }
                    } catch (e) {}

                  } catch (e) {
                    try { console && console.warn && console.warn('PerfPatches: error', e); } catch(_) {}
                  }
                })();
            """.trimIndent()

            // Write file if absent or changed
            try {
                if (!out.exists() || out.readText() != js) {
                    out.writeText(js)
                    Log.i("PerfPatchesModule: wrote patch to " + out.absolutePath)
                } else {
                    Log.i("PerfPatchesModule: perf patch already up-to-date")
                }
            } catch (e: Throwable) {
                Log.e("PerfPatchesModule: failed writing patch", e)
            }
        } catch (e: Throwable) {
            Log.e("PerfPatchesModule:onLoad failed", e)
        }
    }

    override fun buildPayload(builder: JsonObjectBuilder) {
        builder.put("perfPatches", true)
    }
}
