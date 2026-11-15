package com.example.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Rotas tipadas e serializáveis da Etapa 3 usando Navigation Compose 2.8.9.
 * Destino inicial do aplicativo: [AllNotes] ("Todas no meu phone").
 */
@Serializable
sealed interface AnoteVoceDestination {
  @Serializable data object AllNotes : AnoteVoceDestination

  @Serializable data object Pinned : AnoteVoceDestination

  @Serializable data object Trash : AnoteVoceDestination

  @Serializable data class Folder(val folderId: Long) : AnoteVoceDestination
}
