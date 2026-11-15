# AnoteVocê

Aplicativo Android nativo de anotações pessoais com funcionamento prioritariamente offline (versão 1.0 em pt-BR), construído em Kotlin, Jetpack Compose e Material Design 3.

## Recursos principais

- **Armazenamento local offline-first**: Persistência em banco de dados Room (`AnoteVoceDatabase` com migrações e esquemas versionados) e preferências locais via DataStore.
- **Organização por áreas e pastas**: Navegação adaptativa (drawer modal em telas compactas e barra lateral persistente em telas médias/expandidas) entre *Todas no meu phone*, *Fixadas*, *Apagados* e pastas personalizadas.
- **Modos de visualização e temas**: Alternância entre Lista, Grade e Lista Compacta, além de quatro paletas de tema (*Modo Dia*, *Modo Noite*, *Modo Natural* e *Modo Céu*).
- **Motor de edição e recuperação**: Estrutura de conteúdo em blocos JSON versionados com política de preservação de conteúdo não reconhecido e rascunhos de recuperação (`NoteRecoveryDraft`).

## Estrutura e execução local

1. Abra o diretório raiz deste projeto no Android Studio.
2. Execute o módulo `:app` em um emulador ou dispositivo físico Android (`minSdk = 24`, `targetSdk = 36`).
3. Para rodar os testes unitários e Robolectric localmente:
   ```bash
   gradle :app:testDebugUnitTest
   ```
