// The JDK as far as the browser build of :core's JVM half needs it (core/web-sources.txt).
// Kotlin/Wasm has no JDK; these stand in for the few JDK types and functions those files use,
// with the browser's single thread in mind: no lock is needed where there is no second thread,
// and a file is a name, since TDLib keeps its own files in tdweb's storage.
package org.json

typealias JSONObject = com.tmplayer.json.JSONObject
typealias JSONArray = com.tmplayer.json.JSONArray
typealias JSONException = com.tmplayer.json.JSONException
