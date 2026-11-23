package com.pako.eminus4.data.remote.eminus

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.pako.eminus4.core.AppError

/** Convierte las respuestas de Eminus (texto JSON) a modelos. Un error de forma se vuelve [AppError.Parsing]. */
class JsonMapper(val gson: Gson = Gson()) {

    inline fun <reified T> list(body: String): List<T> = parse {
        if (body.isBlank() || body.trim() == "[]") emptyList()
        else gson.fromJson<List<T>>(body, object : TypeToken<List<T>>() {}.type) ?: emptyList()
    }

    inline fun <reified T> obj(body: String): T? = parse {
        if (body.isBlank()) null else gson.fromJson(body, T::class.java)
    }

    fun tree(body: String): JsonElement? = parse { if (body.isBlank()) null else JsonParser.parseString(body) }

    /** Lista de objetos aunque Eminus responda un arreglo, un objeto o un objeto con la lista dentro de [key]. */
    fun objects(body: String, key: String? = null): List<JsonObject> {
        val root = tree(body) ?: return emptyList()
        val array: JsonArray = when {
            root.isJsonArray -> root.asJsonArray
            root.isJsonObject && key != null ->
                root.asJsonObject.get(key)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
            root.isJsonObject -> JsonArray().apply { add(root) }
            else -> JsonArray()
        }
        return array.filter { it.isJsonObject }.map { it.asJsonObject }
    }

    inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: RuntimeException) {
        throw AppError.Parsing(e)
    }
}
