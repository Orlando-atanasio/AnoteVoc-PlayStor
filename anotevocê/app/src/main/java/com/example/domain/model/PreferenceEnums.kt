package com.example.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class AppThemeMode(val labelPtBr: String) {
  @SerialName("modo_dia") MODO_DIA("Modo Dia"),
  @SerialName("modo_noite") MODO_NOITE("Modo Noite"),
  @SerialName("modo_natural") MODO_NATURAL("Modo Natural"),
  @SerialName("modo_ceu") MODO_CEU("Modo Céu"),
}

@Serializable
enum class NotePaperStyle(val labelPtBr: String) {
  @SerialName("linhas") LINHAS("Linhas"),
  @SerialName("sem_linhas") SEM_LINHAS("Sem linhas"),
  @SerialName("pontilhado") PONTILHADO("Pontilhado"),
  @SerialName("elegance_noir") ELEGANCE_NOIR("Élégance Noir"),
  @SerialName("ocean_breeze") OCEAN_BREEZE("Ocean Breeze"),
  @SerialName("sunset_warmth") SUNSET_WARMTH("Sunset Warmth"),
  @SerialName("forest_mist") FOREST_MIST("Forest Mist"),
  @SerialName("minimalist_pro") MINIMALIST_PRO("Minimalist Pro"),
  @SerialName("tech_dark") TECH_DARK("Tech Dark"),
}

@Serializable
enum class HomeViewMode(val labelPtBr: String) {
  @SerialName("lista") LISTA("Lista"),
  @SerialName("grade") GRADE("Grade"),
  @SerialName("lista_compacta") LISTA_COMPACTA("Lista compacta"),
}

data class AppPreferences(
  val themeMode: AppThemeMode = AppThemeMode.MODO_DIA,
  val defaultNotePaper: NotePaperStyle = NotePaperStyle.LINHAS,
  val homeViewMode: HomeViewMode = HomeViewMode.LISTA,
  val updatedAt: Long = 0L,
)
