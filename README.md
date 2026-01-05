# Convos Android

A modern messaging app built on the XMTP protocol, implemented in Jetpack Compose following Clean Architecture principles.

## Overview

Convos is an end-to-end encrypted messaging application that uses the XMTP (Extensible Message Transport Protocol) for secure, decentralized communication. This Android implementation mirrors the iOS app's architecture while leveraging Android best practices.

## Features

### ✅ Implemented
- **Messaging**: Send and receive text messages with real-time updates
- **Conversations**: List, view, and manage conversations
- **Multi-Inbox**: Support for multiple user identities
- **Secure Storage**: Encrypted local database with Room
- **Material Design**: Material3 theming with light/dark mode
- **Deep Linking**: Support for `convos://` and `https://convos.app/` URLs

### 🚧 In Progress
- QR code scanning for invites
- Per-conversation profiles
- Conversation metadata editing

### ⏳ Planned
- Push notifications
- Message reactions
- Message replies
- Background sync
- Image attachments

## Tech Stack

- **UI**: Jetpack Compose with Material3
- **Architecture**: MVVM + Clean Architecture
- **DI**: Hilt
- **Database**: Room Persistence Library
- **Networking**: XMTP Android SDK
- **Async**: Kotlin Coroutines & Flow
- **Image Loading**: Coil
- **Navigation**: Navigation Compose
- **Security**: EncryptedSharedPreferences

## Project Structure

```
app/
├── ui/                 # Compose UI screens and ViewModels
├── navigation/         # Navigation graph
├── data/              # Data layer
│   ├── local/         # Room database
│   ├── repository/    # Repository implementations
│   ├── mapper/        # Data mappers
│   ├── xmtp/          # XMTP client management
│   └── session/       # Session management
├── domain/            # Domain models
└── di/                # Dependency injection modules
```

## Getting Started

### Prerequisites
- Android Studio Ladybug or newer
- JDK 17
- Android SDK 29+

### Building

1. Clone the repository
2. Open in Android Studio
3. Sync Gradle
4. Run the app

```bash
./gradlew assembleDebug
```

### Running Tests

```bash
./gradlew testDebugUnitTest
```

## Architecture

### Data Flow

```
UI (Compose)
    ↓ (StateFlow)
ViewModel
    ↓ (Flow)
Repository
    ↓ ↑
Room DB ← → XMTP SDK
```

### Key Components

**SessionManager**: Manages multiple user inboxes and XMTP client lifecycle

**XMTPClientManager**: Singleton that creates and manages XMTP client instances

**Repositories**: Bridge between local database and XMTP network

**ViewModels**: Expose UI state via StateFlow, handle user actions

## Configuration

### Environment

The app uses `BuildConfig` for environment-specific configuration:
- `DEBUG`: Debug builds with verbose logging
- `RELEASE`: Production builds with ProGuard

### Deep Links

Supported URL patterns:
- `convos://i/{inviteCode}` - Join conversation
- `convos://conversation/{conversationId}` - Open conversation
- `https://convos.app/i/{inviteCode}` - Join conversation (App Link)
- `https://convos.app/conversation/{conversationId}` - Open conversation (App Link)

## Security

- **Encryption Keys**: 32-byte keys stored in EncryptedSharedPreferences
- **Database**: Room database with encryption support
- **XMTP**: End-to-end encrypted messages via XMTP protocol

## XMTP Integration

The app uses the official [xmtp-android](https://github.com/xmtp/xmtp-android) SDK (v4.0.3) for:
- Client creation and management
- Conversation listing and creation
- Message sending and receiving
- Group messaging support

## iOS Parity

This Android app maintains feature parity with the iOS version:
- ✅ Same design tokens (spacing, colors, typography)
- ✅ Equivalent data models
- ✅ Multi-inbox architecture
- ✅ Per-conversation profiles
- 🚧 Feature set (in progress)

## Documentation

- [iOS Convos](../convos-ios/) - Reference iOS implementation

## Support

For issues and questions, please refer to the main XMTP documentation or create an issue in this repository.