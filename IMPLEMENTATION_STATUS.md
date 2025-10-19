# Convos Android - Implementation Status

## Overview
This is the Android implementation of Convos, a messaging app built on the XMTP protocol. The app is being built with Jetpack Compose and follows modern Android architecture patterns.

## Completed ✅

### 1. Project Configuration
- **Java 17** - Upgraded from Java 11 to meet xmtp-android requirements
- **Jetpack Compose** - Latest Compose BOM with Material3
- **Kotlin 2.0.21** - Latest stable Kotlin
- **Build Variants** - Debug and Release configurations

### 2. Dependencies
All major dependencies have been added via version catalog:
- ✅ **XMTP Android SDK** (4.0.3)
- ✅ **Jetpack Compose** with Material3
- ✅ **Hilt** for dependency injection
- ✅ **Room** for local database
- ✅ **Coil** for image loading
- ✅ **CameraX + ML Kit** for QR scanning
- ✅ **Firebase** (Analytics, Messaging)
- ✅ **Kotlin Coroutines & Flow**
- ✅ **Timber** for logging

### 3. App Foundation
- ✅ **ConvosApplication** - Hilt-enabled Application class with Timber setup
- ✅ **MainActivity** - Main activity with Compose integration
- ✅ **ConvosApp** - Root composable (placeholder)
- ✅ **AndroidManifest** - Permissions and deep link intent filters configured

### 4. Design System
Complete Material3 theme matching iOS design:
- ✅ **Color.kt** - Full color palette (light/dark mode)
- ✅ **Dimensions.kt** - Spacing, corner radius, image sizes, font sizes
- ✅ **Type.kt** - Material3 typography scale
- ✅ **Theme.kt** - Theme configuration with dynamic color support

Color system includes:
- Background colors (primary, inverted)
- Fill colors (primary, secondary, tertiary, minimal)
- Text colors (primary, secondary, tertiary)
- Border colors (subtle variants)
- Bubble colors (outgoing/incoming)
- Semantic colors (caution, success, standard)

### 5. Database Layer (Room)
Complete Room database schema:

**Entities:**
- ✅ `InboxEntity` - User accounts/identities
- ✅ `ConversationEntity` - Groups and DMs
- ✅ `MessageEntity` - Chat messages
- ✅ `MemberEntity` - Conversation members
- ✅ `ProfileEntity` - Per-conversation user profiles
- ✅ `ReactionEntity` - Message reactions
- ✅ `InviteEntity` - Conversation invites

**DAOs:**
- ✅ `InboxDao` - Inbox CRUD operations
- ✅ `ConversationDao` - Conversation queries with Flow
- ✅ `MessageDao` - Message queries and updates
- ✅ `MemberDao` - Member management
- ✅ `ProfileDao` - Profile queries
- ✅ `ReactionDao` - Reaction management

**Database:**
- ✅ `ConvosDatabase` - Room database with all DAOs

### 6. Domain Models
Clean architecture domain layer:
- ✅ `Conversation` - with ConsentState and ConversationKind enums
- ✅ `Message` - with MessageContent sealed class and MessageStatus enum
- ✅ `Member` - with PermissionLevel enum
- ✅ `Profile` - Per-conversation profile model
- ✅ `Reaction` - Emoji reaction model
- ✅ `Inbox` - User account model

### 7. XMTP Integration
Core XMTP client management:
- ✅ `XMTPClientManager` - Singleton manager for multiple XMTP clients
  - Client creation and lifecycle management
  - Active inbox tracking
  - Multi-inbox support
- ✅ `EncryptionKeyManager` - Secure key storage
  - Uses EncryptedSharedPreferences
  - 32-byte key generation per address
  - Secure key retrieval and storage

### 8. Dependency Injection
- ✅ `DatabaseModule` - Provides Room database and all DAOs
- Hilt configured throughout the app

## Recently Completed 🎉

### Repository Layer
- ✅ **ConversationRepository** - Sync, list, update conversations
- ✅ **MessageRepository** - Send messages, sync messages
- ✅ **ConversationMapper** - Entity to domain model conversion
- ✅ **MessageMapper** - Entity to domain model conversion
- ✅ **SessionManager** - Multi-inbox session management with state tracking

### UI Screens
- ✅ **Conversations List** - Full implementation with swipe-to-delete
- ✅ **Conversation Detail/Chat** - Message bubbles, send messages, real-time updates
- ✅ **Settings Screen** - App version, delete all data
- ✅ **Navigation** - Deep linking configured for all routes

## In Progress 🚧

### Advanced Features
Need to implement:
- New Conversation (QR scanner)
- Profile Edit per conversation
- Conversation Info editing

## Not Started ⏳

### Syncing
- Background message sync with WorkManager
- Real-time XMTP stream management
- Push notifications with FCM
- Message delivery status tracking

### Advanced Features
- Message reactions UI
- Message replies functionality
- Conversation metadata editing (name, description, image)
- Member management (add/remove)
- Image uploads to CDN
- QR code generation/scanning for invites
- Profile management per conversation

## Architecture Overview

```
app/
├── ConvosApplication.kt           [✅ Application class with Hilt]
├── MainActivity.kt                 [✅ Main activity]
├── ConvosApp.kt                    [✅ Root composable with navigation]
│
├── ui/
│   ├── theme/                      [✅ Design system]
│   │   ├── Color.kt
│   │   ├── Dimensions.kt
│   │   ├── Type.kt
│   │   └── Theme.kt
│   │
│   ├── conversations/              [✅ Conversations list]
│   │   ├── ConversationsScreen.kt
│   │   ├── ConversationsViewModel.kt
│   │   └── ConversationListItem.kt
│   │
│   ├── conversation/               [✅ Chat/messaging]
│   │   ├── ConversationScreen.kt
│   │   └── ConversationViewModel.kt
│   │
│   └── settings/                   [✅ Settings]
│       ├── SettingsScreen.kt
│       └── SettingsViewModel.kt
│
├── navigation/                     [✅ Navigation setup]
│   └── Navigation.kt
│
├── data/
│   ├── local/                      [✅ Room database]
│   │   ├── entity/                 (All entities)
│   │   ├── dao/                    (All DAOs)
│   │   └── ConvosDatabase.kt
│   │
│   ├── xmtp/                       [✅ XMTP integration]
│   │   ├── XMTPClientManager.kt
│   │   └── EncryptionKeyManager.kt
│   │
│   ├── session/                    [✅ Session management]
│   │   └── SessionManager.kt
│   │
│   ├── repository/                 [✅ Repositories]
│   │   ├── ConversationRepository.kt
│   │   └── MessageRepository.kt
│   │
│   └── mapper/                     [✅ Data mappers]
│       ├── ConversationMapper.kt
│       └── MessageMapper.kt
│
├── domain/
│   └── model/                      [✅ Domain models]
│       ├── Conversation.kt
│       ├── Message.kt
│       ├── Member.kt
│       ├── Profile.kt
│       ├── Reaction.kt
│       └── Inbox.kt
│
└── di/                             [✅ Dependency injection]
    └── DatabaseModule.kt
```

## Core Features Implemented ✅

### Messaging
- ✅ Send text messages through XMTP
- ✅ Display messages in conversation view
- ✅ Message bubbles with timestamps
- ✅ Real-time message updates via Room Flow
- ✅ Sync messages from XMTP to local database

### Conversations
- ✅ List all allowed conversations
- ✅ Swipe-to-delete conversations
- ✅ Empty state handling
- ✅ Pull-to-refresh (sync)
- ✅ Navigate to conversation detail

### Session Management
- ✅ Multi-inbox support
- ✅ Session state tracking (NoSession, Creating, Active, Error)
- ✅ Secure encryption key management
- ✅ Client lifecycle management

### Data Layer
- ✅ Room database with 7 entities
- ✅ Flow-based reactive queries
- ✅ Repository pattern
- ✅ Data mappers (Entity ↔ Domain)
- ✅ XMTP SDK integration

### UI/UX
- ✅ Material3 design system
- ✅ Light/dark mode support
- ✅ Navigation with deep linking
- ✅ Loading/error/empty states
- ✅ Responsive layouts

## Next Steps (Priority Order)

1. **Background Sync & Streaming**
   - WorkManager for periodic sync
   - XMTP message streaming
   - Real-time message delivery

2. **New Conversation Flow**
   - QR code scanner with CameraX + ML Kit
   - Manual invite code entry
   - Join conversation from invite

3. **Profile Management**
   - Per-conversation profile editing
   - Display name customization
   - Avatar upload/selection

4. **Conversation Management**
   - Edit conversation metadata
   - View/manage members
   - Leave/explode conversation

5. **Push Notifications**
   - Firebase Cloud Messaging setup
   - Background message decryption
   - Notification channels

6. **Advanced Messaging**
   - Message reactions
   - Reply to messages
   - Attachment support

## Technical Decisions

### Multi-Inbox Architecture
The app supports multiple user identities (inboxes) simultaneously, matching the iOS implementation. Each inbox has its own XMTP client instance managed by XMTPClientManager.

### Per-Conversation Profiles
Following iOS, users can have different display names and avatars per conversation, not a global profile.

### Database-First Approach
All data is stored in Room database first, with XMTP as the source of truth. This ensures offline support and fast UI updates.

### Modern Android Stack
- Jetpack Compose for UI (no XML layouts)
- Hilt for DI (compile-time safety)
- Room for database (type-safe queries)
- Kotlin Coroutines & Flow (reactive patterns)
- Material3 (latest design system)

## Build Status

The project is configured and should build successfully. All dependencies are properly declared in the version catalog.

To build:
```bash
./gradlew assembleDebug
```

To run tests:
```bash
./gradlew testDebugUnitTest
```

## iOS Parity

This implementation is designed to match the iOS app's architecture:
- ✅ Equivalent design tokens (spacing, colors, typography)
- ✅ Same data models (Inbox, Conversation, Message, etc.)
- ✅ Multi-inbox support
- ✅ Per-conversation profiles
- ⏳ Same features (in progress)

## Notes

- The app uses Java 17 (required by xmtp-android SDK)
- Minimum SDK is 29 (Android 10)
- Target SDK is 36
- Deep links configured for both `https://convos.app/i/*` and `convos://i/*`
- Camera permission required for QR scanning
- Post notifications permission for push notifications
