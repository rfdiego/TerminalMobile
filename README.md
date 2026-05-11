# TerminalMobile

Terminal remoto com suporte ao **Claude Code** — acesse o terminal do seu PC pelo celular, de qualquer lugar.

```
Browser / Android  ←── WebSocket ──→  Node.js Server  ←── PTY ──→  Claude Code
```

---

## Clientes disponíveis

| Cliente | Descrição |
|---------|-----------|
| **Web Terminal** (`web-terminal.html`) | Abre direto no navegador, sem instalar nada |
| **Android APK** (`android/`) | App nativo Kotlin + Compose (em desenvolvimento) |

---

## Quick Start — Servidor

**Pré-requisito:** Node.js 18+

```bat
cd server
install.bat        # instala dependências
start.bat          # inicia na porta 8765
```

Linux / Mac:
```bash
cd server && ./install.sh && ./start.sh
```

O servidor imprime o **PIN de 4 dígitos** no console ao iniciar:

```
════════════════════════════════════════
  TerminalMobile Server
════════════════════════════════════════
  PIN: 1042
════════════════════════════════════════
```

Edite `server/.env` para fixar o PIN e outras configurações:

```env
PORT=8765
AUTH_TOKEN=1042
MAX_SESSIONS=10
REPOS_DIR=C:\Projects\Repository
INITIAL_COMMAND=claude --dangerously-skip-permissions
```

---

## Web Terminal

O arquivo `web-terminal.html` é servido automaticamente pelo servidor em `http://IP:8765/`.

### Acesso na rede local (mesmo WiFi)

O servidor exibe o IP ao iniciar:
```
[Server] Ready on:
  ws://192.168.15.40:8765   ← use this
```

Abra `http://192.168.15.40:8765` no celular ou em qualquer navegador da rede.

### Acesso externo via ngrok

```bash
# instalar (uma vez)
winget install ngrok.ngrok --accept-package-agreements
ngrok config add-authtoken SEU_TOKEN   # token em ngrok.com

# rodar junto com o servidor
ngrok http 8765
```

Acesse a URL gerada (`https://abc.ngrok-free.app`) — os campos de host, porta e WSS são preenchidos automaticamente.

### Wizard de 3 passos

| Passo | Descrição |
|-------|-----------|
| **1 — Conexão** | IP/host, porta e PIN (com validação em tempo real contra o servidor) |
| **2 — Projeto** | Lista repos GitHub clonados localmente; clona todos automaticamente |
| **3 — Tecnologia** | Escolha Claude Code ou Claude (auto) e acompanhe o launch sequence |

---

## Integração com GitHub

No passo 2 do wizard, conecte sua conta GitHub com um **Personal Access Token** (`repo` scope):

- O token é salvo no `server/.env` e não precisa ser inserido novamente
- Todos os repositórios são clonados automaticamente em `REPOS_DIR`
- Progresso de clone exibido em tempo real
- Botão "Path" abre a pasta do projeto no Explorer
- Opção de remover todos os repos locais e re-sincronizar

---

## Arquitetura

```
TerminalMobile/
├── web-terminal.html        # Web client (xterm.js + WebSocket)
├── server/
│   ├── src/
│   │   ├── index.js         # Entry point, exibe PIN, inicia servidor
│   │   ├── wsServer.js      # HTTP + WebSocket handler, rotas REST
│   │   ├── ptyManager.js    # Gerenciamento de sessões PTY
│   │   ├── repoManager.js   # Scan local, GitHub API, clone-all
│   │   └── auth.js          # Validação de PIN (timing-safe)
│   ├── .env.example
│   ├── package.json
│   ├── install.bat / install.sh
│   └── start.bat / start.sh
└── android/                 # App Android (Kotlin + Compose) — em desenvolvimento
    └── app/src/main/java/com/terminalmobile/
```

---

## API REST do Servidor

Todas as rotas (exceto `GET /`) exigem `Authorization: Bearer <PIN>`.

| Método | Rota | Descrição |
|--------|------|-----------|
| `GET` | `/` | Serve o `web-terminal.html` |
| `GET` | `/api/repos` | Lista repos locais + GitHub |
| `GET` | `/api/clone-status` | Progresso do clone-all em background |
| `POST` | `/api/github-token` | Salva token GitHub no `.env`, inicia clone-all |
| `POST` | `/api/clone-all` | Inicia clone de todos os repos não clonados |
| `POST` | `/api/open-folder` | Abre pasta no Explorer (Windows) |
| `POST` | `/api/remove-all-repos` | Remove todos os repos clonados localmente |

---

## Protocolo WebSocket

### Cliente → Servidor
| Mensagem | Descrição |
|----------|-----------|
| `{"type":"auth","token":"...","cols":120,"rows":40}` | Autenticar |
| `{"type":"input","data":"..."}` | Enviar texto ao terminal |
| `{"type":"resize","cols":120,"rows":40}` | Redimensionar |
| `{"type":"new_session"}` | Nova sessão PTY |
| `{"type":"switch_session","sessionId":"..."}` | Trocar sessão ativa |
| `{"type":"kill_session","sessionId":"..."}` | Encerrar sessão |
| `{"type":"ping"}` | Keepalive |

### Servidor → Cliente
| Mensagem | Descrição |
|----------|-----------|
| `{"type":"auth_success","sessionId":"...","sessions":[...]}` | Auth OK |
| `{"type":"output","data":"..."}` | Saída do terminal (streaming) |
| `{"type":"session_exit","code":0}` | Processo PTY encerrado |
| `{"type":"error","message":"..."}` | Erro |
| `{"type":"pong","ts":1234567890}` | Resposta ao ping |

---

## Variáveis de Ambiente

| Variável | Padrão | Descrição |
|----------|--------|-----------|
| `PORT` | `8765` | Porta do servidor |
| `AUTH_TOKEN` | (gerado) | PIN de 4 dígitos |
| `MAX_SESSIONS` | `10` | Máximo de sessões PTY simultâneas |
| `REPOS_DIR` | `~/projects` | Diretório onde os repos são clonados |
| `SHELL_PATH` | (padrão do sistema) | Shell a usar |
| `INITIAL_COMMAND` | (nenhum) | Comando executado ao abrir o shell |
| `GITHUB_TOKEN` | (nenhum) | Token GitHub (salvo automaticamente pelo wizard) |
| `TLS_ENABLED` | `false` | Ativar WSS/HTTPS |
| `TLS_CERT_PATH` | `./certs/cert.pem` | Certificado TLS |
| `TLS_KEY_PATH` | `./certs/key.pem` | Chave privada TLS |

---

## Segurança

- PIN validado com `crypto.timingSafeEqual` (resistente a timing attack)
- Conexões não autenticadas são encerradas após 15 segundos
- Sessões persistem no servidor ao desconectar (permite retomar)
- Para acesso externo, use ngrok (TLS automático) ou ative `TLS_ENABLED=true`
- O `.env` com tokens nunca é commitado (está no `.gitignore`)

---

## Solução de Problemas

**node-pty falha no Windows:**
```bat
npm install --global windows-build-tools
```
Ou instale o [Visual Studio Build Tools](https://aka.ms/vs/17/release/vs_BuildTools.exe) com "Desktop development with C++".

**"Connection refused" ao tentar localhost de outro dispositivo:**
`localhost` aponta para o próprio dispositivo. Use o IP da máquina servidora.

**Porta 8765 bloqueada:**
Crie uma regra no Firewall do Windows:
```powershell
New-NetFirewallRule -DisplayName "TerminalMobile" -Direction Inbound -Protocol TCP -LocalPort 8765 -Action Allow
```

**Gradle sync falha (Android):**
- File → Invalidate Caches → Restart
- Verifique JDK 17: File → Project Structure → SDK Location
