package com.tmplayer.online

import com.tmplayer.platform.IOException
import kotlin.js.Promise
import kotlinx.coroutines.await

private fun jsFetch(method: String, url: String, headersJson: String, body: String?): Promise<JsString> = js("""
    (async () => {
      const init = { method: method, headers: JSON.parse(headersJson), credentials: 'omit' };
      if (body !== null) init.body = body;
      const r = await fetch(url, init);
      const bytes = new Uint8Array(await r.arrayBuffer());
      let s = '';
      for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
      const headers = {};
      r.headers.forEach((v, k) => { headers[k] = v; });
      return JSON.stringify({ status: r.status, headers: headers, body: btoa(s) });
    })()
""")

/**
 * [HttpTransport] over the browser's fetch. Only providers that answer with CORS headers can be
 * reached this way (TMDB, TVmaze, AniList and their picture hosts do); anything else fails as a
 * network error, never through a proxy of ours. A failure to connect is an [IOException], as on
 * the JVM.
 *
 * The providers' `User-Agent` is left out: the browser names itself, and a cross origin request
 * carrying that header needs a preflight, a second round trip for every lookup and every poster.
 * Pictures come through the browser's own HTTP cache (TMDB's are kept for a year), so a reload
 * draws them without the network.
 */
object FetchTransport : HttpTransport {
    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    override suspend fun send(request: HttpRequest): HttpResponse {
        val sent = request.headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) }
        val headers = com.tmplayer.json.JSONObject(sent).toString()
        val text = try {
            jsFetch(request.method, request.url, headers, request.body).await<JsString>().toString()
        } catch (e: Throwable) {
            throw IOException(e.message ?: "fetch failed")
        }
        val answer = com.tmplayer.json.JSONObject(text)
        val headerMap = answer.optJSONObject("headers")?.let { h -> h.keySet().associateWith { h.optString(it) } }.orEmpty()
        val body = kotlin.io.encoding.Base64.decode(answer.optString("body"))
        return HttpResponse(answer.optInt("status"), headerMap, body)
    }
}
