# CLAUDE.md

This file provides guidance to Claude Code when working with code in this repository.

## Project Overview

This is the **Convos Android** repository - a modern messaging application built on the XMTP protocol. It's the Android counterpart to convos-ios, implementing end-to-end encrypted messaging with ephemeral conversations ("Convos that explode"). The app uses Jetpack Compose and follows Clean Architecture with a multi-inbox design where each conversation has its own identity.

## Architecture

### Core Concepts

**Backend-less Invites**: The app uses cryptographically signed invites sent via XMTP DMs. No backend API is required for the invite flow - everything is peer-to-peer.

**Multi-Inbox Architecture**: Each conversation can have its own XMTP identity. Users don't have a global profile - instead they have per-conversation profiles (display name, avatar).

**Tag-Based Group Matching**: When someone joins via invite, they send a DM to the creator with a signed invite code. The creator validates the signature and adds them to the group. The joiner then observes the conversation stream and matches the group's `tag` (stored in custom metadata) against their pending invites to determine if they should join.

### Project Structure

```
app/
├── crypto/                 # Cryptographic utilities for invites
│   ├── Base64URL.kt       # URL-safe base64 encoding
│   ├── InviteCode.kt      # ChaCha20-Poly1305 encryption for conversation tokens
│   └── SignedInviteValidator.kt  # secp256k1 signature verification
│
├── proto/                  # Protocol Buffer definitions
│   └── invite.proto       # SignedInvite, InvitePayload, ConversationCustomMetadata
│
├── ui/                     # Jetpack Compose UI layer
│   ├── theme/             # Material3 design system
│   ├── conversations/     # Conversations list screen
│   ├── conversation/      # Chat/messaging screen
│   ├── newconversation/   # QR scanner & invite joining
│   └── settings/          # App settings
│
├── data/
│   ├── local/             # Room database (entities, DAOs)
│   ├── api/               # API models (legacy, mostly removed)
│   ├── invite/            # Invite join request processing
│   │   └── InviteJoinRequestsManager.kt
│   ├── repository/        # Repository pattern implementations
│   ├── mapper/            # Entity ↔ Domain model conversion
│   ├── xmtp/              # XMTP client management
│   │   ├── XMTPClientManager.kt      # Manages multiple XMTP clients
│   │   └── EncryptionKeyManager.kt   # Secure key storage
│   └── session/           # Session state management
│       └── SessionManager.kt
│
├── domain/
│   └── model/             # Domain models (Conversation, Message, etc.)
│
└── di/                    # Hilt dependency injection modules
```

## Common Development Commands

### Build & Development
```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK (requires signing config)
./gradlew clean                  # Clean build artifacts
./gradlew generateDebugProto     # Generate protobuf classes
```

### Testing
```bash
./gradlew testDebugUnitTest      # Run unit tests
./gradlew connectedDebugAndroidTest  # Run instrumented tests (requires device/emulator)
```

### Code Quality
```bash
./gradlew lint                   # Run Android lint
./gradlew lintDebug              # Run lint on debug variant
```

## Key Technical Details

### Invite System Flow

**Creating an Invite:**
1. Generate a random `tag` (UUID) for the conversation
2. Create `ConversationCustomMetadata` with tag, description, profiles, expiration
3. Store metadata in `Group.description` (serialized protobuf)
4. Create `InvitePayload` with encrypted conversation token, creator inbox ID, and tag
5. Sign payload with installation key to create `SignedInvite`
6. Base64 URL encode the SignedInvite to create shareable link

**Joining via Invite:**
1. Parse base64 URL to `SignedInvite`
2. Verify signature using creator's installation ID
3. Check invite expiration
4. Send the invite code as a DM to `creator_inbox_id`
5. Store pending invite with tag for later matching
6. Show "Waiting for approval" UI

**Creator Processes Join Request:**
1. Receive DM and attempt to parse as `SignedInvite`
2. Verify signature (if invalid, block sender's inbox)
3. Verify creator inbox ID matches
4. Decrypt conversation token to get group ID
5. Find group and add sender to members
6. Update DM consent state to ALLOWED

**Joiner Matches Group:**
1. Observe conversation stream for new groups
2. When new group appears, parse `ConversationCustomMetadata` from `Group.description`
3. Check if group's `tag` matches any pending invite
4. If match: update consent to ALLOWED and remove from pending
5. If no match: update consent to DENIED

### Protocol Buffers

The app uses Protocol Buffers for:
- **SignedInvite**: Cryptographically signed invite data
- **InvitePayload**: Encrypted conversation token + metadata
- **ConversationCustomMetadata**: Stored in Group.description (profiles, tag, expiration)
- **ConversationProfile**: Per-member display name and image

Location: `app/src/main/proto/invite.proto`
Generated: `app/build/generated/source/proto/debug/java/`

To regenerate after proto changes:
```bash
./gradlew generateDebugProto
```

### Cryptography

**Conversation Token Encryption:**
- Algorithm: ChaCha20-Poly1305 AEAD
- Key Derivation: HKDF-SHA256 from installation key
- Salt: "ConvosInviteV1"
- Nonce: 12 bytes random
- AAD: Creator inbox ID

**Invite Signing:**
- Algorithm: secp256k1 (via XMTP SDK)
- Signs: SHA-256 hash of InvitePayload protobuf bytes
- Via: `Client.signWithInstallationKey()`
- Verify: `Client.verifySignatureWithInstallationId()`

### XMTP Integration

**SDK Version**: 4.5.6 (org.xmtp:android)

**Key Classes:**
- `XMTPClientManager`: Manages multiple XMTP Client instances (one per inbox)
- `Client`: XMTP client for messaging operations
- `Group`: Group conversation (uses MLS)
- `Dm`: Direct message conversation

**Client Lifecycle:**
```kotlin
// Create client
val client = Client().create(
    account = privateKeyBundle,
    options = ClientOptions(
        api = api,
        env = XMTPEnvironment.DEV,
        appContext = context,
        dbEncryptionKey = encryptionKey
    )
)

// The client's installationId is used as the signing key
val installationId: String = client.installationId
```

### Database Schema (Room)

**Entities:**
- `InboxEntity`: User identities (inbox ID, address, encrypted key)
- `ConversationEntity`: Groups and DMs (ID, topic, name, consent state)
- `MessageEntity`: Chat messages (ID, content, sender, status, timestamp)
- `MemberEntity`: Conversation members (inbox ID, permissions)
- `ProfileEntity`: Per-conversation profiles (name, image URL)
- `ReactionEntity`: Message reactions (emoji, sender)
- `InviteEntity`: Conversation invites (invite code, expiration)

**Key DAOs:**
- `ConversationDao`: Flow-based queries for reactive UI updates
- `MessageDao`: Paginated message queries, real-time updates
- `InboxDao`: Active inbox management

### Design System

**Material3 Theme** matching iOS design tokens:
- Light/Dark mode support
- Color palette: Background, Fill, Text, Border, Bubble, Semantic colors
- Typography: iOS-inspired font sizes and weights
- Spacing: Consistent spacing scale (xs, sm, md, lg, xl, xxl)
- Dimensions: Corner radius, image sizes, bubble configurations

Location: `app/src/main/java/com/naomiplasterer/convos/ui/theme/`

## Important Implementation Details

### No Backend API for Invites

The app previously had backend API code (`ConvosApiService`, `InviteRepository`) but this has been **completely removed**. All invite operations are now peer-to-peer via XMTP DMs. The only backend interaction may be for push notifications (future).

### Pending Invites Storage

`InviteJoinRequestsManager` maintains an in-memory map of pending invites:
```kotlin
private val pendingInvites = mutableMapOf<String, SignedInvite>()
```

When a join request is sent, the invite is stored with its tag. When new groups appear, they are checked against pending invites by tag. **Note**: This is in-memory only and will be lost on app restart. Production may want persistent storage.

### Consent States

Groups have consent states that determine visibility:
- `ALLOWED`: Show in conversation list
- `DENIED`: Hide from list
- `UNKNOWN`: Pending approval

The tag-matching system automatically sets consent based on whether the group matches a pending invite.

### Deep Linking

Supported URL patterns:
- `convos://i/{inviteCode}` - Join conversation
- `https://convos.app/i/{inviteCode}` - App Link for joining
- `https://popup.convos.org/v2?i={inviteCode}` - Legacy iOS format

The app extracts the invite code from various URL formats and processes them identically.

## Dependencies (Version Catalog)

Key dependencies in `gradle/libs.versions.toml`:
- **xmtp-android**: 4.5.6
- **compose-bom**: 2025.10.00
- **kotlin**: 2.1.0
- **hilt**: 2.57.2
- **room**: 2.8.2
- **protobuf**: 4.29.4
- **tink-android**: 1.18.0 (crypto utilities)
- **coil**: 2.7.0 (image loading)
- **cameraX**: 1.5.1 (QR scanning)

## Testing

### Unit Tests
Test ViewModels, Repositories, and utility classes. Use JUnit 4 with MockK for mocking.

### Instrumented Tests
Test database operations and UI flows. Requires Android device or emulator.

Location: `app/src/test/` and `app/src/androidTest/`

## Build Configuration

**Minimum SDK**: 29 (Android 10)
**Target SDK**: 36
**Java Version**: 17 (required by xmtp-android)
**Build Tools**: Android Gradle Plugin 8.13.0

## Common Issues & Solutions

### Protobuf Generation
If protobuf classes aren't found, run:
```bash
./gradlew clean generateDebugProto
```

### XMTP Client Errors
- Ensure encryption key is 32 bytes
- **Check XMTP environment matches**: The app defaults to `XMTPEnvironment.PRODUCTION` to match convos-agent production environment. If testing with a DEV environment, explicitly pass `XMTPEnvironment.DEV` when creating clients.
- Verify installationId is used consistently for signing
- **"sequenceId not found in local db" error**: This typically indicates an environment mismatch - ensure both the Android app and the invite creator (e.g., convos-agent) are on the same XMTP network (both DEV or both PRODUCTION)

### Deep Link Not Working
- Check AndroidManifest intent-filter configuration
- Verify URL format matches extraction regex in `NewConversationViewModel`
- Test with `adb shell am start -a android.intent.action.VIEW -d "convos://i/testcode"`

## iOS Parity

This Android app mirrors the iOS implementation:
- ✅ Same cryptographic invite system
- ✅ Same protobuf definitions
- ✅ Same tag-based group matching
- ✅ Same multi-inbox architecture
- ✅ Same design tokens (colors, spacing, typography)
- ✅ Backend-less operation (no API for invites)

Refer to `../convos-ios/` for iOS reference implementation.

## Important Notes

- **No Firebase AppCheck**: Removed in favor of backend-less invites
- **Per-conversation profiles**: Users don't have global profiles
- **Ephemeral conversations**: Groups can have expiration dates
- **Installation-based signing**: Uses XMTP installationId, not wallet signatures
- **Tag uniqueness**: Each conversation should have a unique tag (UUID recommended)
- **Production Environment by Default**: The app defaults to `XMTPEnvironment.PRODUCTION` to ensure compatibility with production QR codes from convos-agent and the web portal

## Additional Documentation

- [README.md](README.md) - Project overview and setup
- [iOS Docs](../convos-ios/CLAUDE.md) - iOS reference implementation
- [XMTP Android SDK](https://github.com/xmtp/xmtp-android) - Official SDK documentation

## Support

For questions about:
- **XMTP Protocol**: See [xmtp.org](https://xmtp.org)
- **Android SDK**: See [github.com/xmtp/xmtp-android](https://github.com/xmtp/xmtp-android)
- **Convos Architecture**: Refer to iOS implementation or ask maintainers
