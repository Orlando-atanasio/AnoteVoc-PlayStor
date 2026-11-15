package com.example.core.serialization

import kotlinx.serialization.json.Json

/**
 * Instância centralizada de [Json] (kotlinx.serialization) para documentos de notas
 * e futuros payloads estruturados, mantendo compatibilidade com evoluções de esquema.
 */
object AnoteVoceJson {
  val instance: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    classDiscriminator = "type"
    isLenient = false
  }
}
