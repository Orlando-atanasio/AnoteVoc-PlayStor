package com.example.core.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Contrato para injeção de dispatchers de Coroutines, facilitando testes unitários
 * e a futura camada assíncrona com Flow, Room e DataStore.
 */
interface DispatcherProvider {
  val main: CoroutineDispatcher
  val io: CoroutineDispatcher
  val default: CoroutineDispatcher
}

class DefaultDispatcherProvider : DispatcherProvider {
  override val main: CoroutineDispatcher = Dispatchers.Main
  override val io: CoroutineDispatcher = Dispatchers.IO
  override val default: CoroutineDispatcher = Dispatchers.Default
}
