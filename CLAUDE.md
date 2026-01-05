# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## Project Overview

**Convos Android** - An end-to-end encrypted messaging app built on XMTP protocol using Jetpack Compose and Clean Architecture.

### Core Concepts

**Multi-Inbox Architecture**: Each conversation has its own XMTP identity. Users have per-conversation profiles, not global profiles.

**Backend-less Invites**: Cryptographically signed invites sent peer-to-peer via XMTP DMs. No backend API required.

**Tag-Based Group Matching**: When joining via invite, users send a signed invite code to the creator. After approval, joiners match new groups by comparing their `tag` (UUID stored in group metadata) against pending invites.

### Project Structure

```
app/
├── ui/                     # Jetpack Compose screens & ViewModels
├── data/
│   ├── local/             # Room database (entities, DAOs)
│   ├── repository/        # Repository pattern
│   ├── mapper/            # Entity ↔ Domain conversion
│   ├── xmtp/              # XMTP client management
│   └── session/           # Session/inbox management
├── domain/model/          # Domain models
├── crypto/                # Invite signing & encryption
├── proto/                 # Protocol Buffer definitions
└── di/                    # Hilt dependency injection
```

## Development Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew testDebugUnitTest      # Run unit tests
./gradlew generateDebugProto     # Regenerate protobuf classes
./gradlew lint                   # Run Android lint
```

## Architecture Layers

**ConversationEntity** (Data) → **Conversation** (Domain) → **UI**
- Entities use primitives for Room storage (String, Long, Boolean)
- Domain models use type-safe enums and rich objects
- Mappers (`toDomain()`, `toEntity()`) convert between layers

## Important Notes

### XMTP Environment
- **Defaults to PRODUCTION** - Matches production QR codes from web portal
- For DEV testing, explicitly pass `XMTPEnvironment.DEV` when creating clients
- **Environment mismatch** causes "sequenceId not found" errors

### Database Schema
Current entities:
- `InboxEntity` - User identities
- `ConversationEntity` - Groups/DMs (no members stored here)
- `MessageEntity` - Chat messages
- `MemberProfileEntity` - Display names/avatars (loaded separately)

Database version: 5

### Invite Flow
1. Creator generates invite with unique `tag` (UUID) stored in group metadata
2. Joiner sends signed invite code via DM to creator
3. Creator validates signature and adds joiner to group
4. Joiner observes new groups, matches by `tag`, updates consent

### Pending Invites
`InviteJoinRequestsManager` stores pending invites **in-memory only** (lost on restart).

### Consent States
- `ALLOWED` - Show in conversation list
- `DENIED` - Hidden
- `UNKNOWN` - Pending approval

## Common Issues

### Protobuf Classes Not Found
```bash
./gradlew clean generateDebugProto
```

### XMTP Environment Mismatch
- Ensure all clients (Android, web, agent) use same environment (DEV or PRODUCTION)
- Check `XMTPEnvironment` parameter in client creation

### Deep Links
Test with: `adb shell am start -a android.intent.action.VIEW -d "convos://i/testcode"`

Supported formats:
- `convos://i/{code}`
- `https://convos.app/i/{code}`
- `https://popup.convos.org/v2?i={code}`
