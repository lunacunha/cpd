# TLS Chat System

A secure multi-user chat application built with Java, featuring TLS encryption, persistent user sessions, and integrated AI chatbot support using Ollama.

## Features

- **Secure Communication**: TLS 1.2/1.3 encryption with custom certificates
- **Multi-user Chat Rooms**: Create and join different chat rooms
- **AI Integration**: Chat with AI bots powered by Ollama (llama3.2:1b model)
- **Persistent Sessions**: Automatic token-based session management
- **User Authentication**: Registration and login system with password hashing
- **Room Persistence**: Automatically rejoin your last room on reconnection
- **High Concurrency**: Virtual threads with custom thread-safe implementations

## Concurrency Design

- **Virtual Threads**: All client connections are handled by Java 21+ virtual threads for maximum scalability and efficiency
- **Custom Thread Safety**: Uses java.util.concurrent.locks.ReadWriteLock 
- **Lock Strategy**: Separate read/write locks for rooms, users, and active clients to minimize contention
- **Race Condition Prevention**: Proper synchronization for all shared data structures without deadlocks

## Thread-Safe Components

- **ChatRoom**: Protected by ReadWriteLock for user management 
- **UserManager**: Thread-safe user authentication and state management
- **Server**: Concurrent client handling with protected active client registry
- **Message Broadcasting**: Lock-protected operations for real-time message delivery

## Fault Tolerance

- **Automatic Reconnection**: Client reconnects up to 5 times with 2-second delays on connection failure
- **Session Persistence**: Server maintains complete user state across disconnections
- **Room Persistence**: Users automatically rejoin their last room after reconnection 
- **Graceful Error Handling**: Handles network failures, socket errors, and server unavailability
- **State Recovery**: Both client and server preserve session state during temporary disconnections
- **Connection Validation**: Server validates connection health and manages client lifecycle

### Reconnection Process

1. Client detects connection loss
2. Automatic reconnection attempts (up to 5 times)
3. Token-based re-authentication
4. Automatic room rejoining

## Security Implementation

- **TLS 1.2/1.3**: Enforced cipher suites
- **Certificate Validation**: Custom truststore setup 
- **Password Security**: SHA-256 hashing with proper encoding
- **Token Management**: 3-day token lifetime with secure validation
- **Session Security**: Tokens replace credentials after initial authentication

### Setup Ollama (for AI features)
1. Install Ollama from [ollama.com](https://ollama.com/)
2. In your terminal:
```bash
ollama run llama3.2:1b
```


### 1. Generate TLS Certificates

**Create Server Keystore:**
```bash
keytool -genkeypair \
  -alias server \
  -keyalg RSA \
  -keysize 2048 \
  -validity 365 \
  -keystore server.jks \
  -storepass [keystore-password] \
  -keypass [keystore-password] \
  -dname "CN=localhost, OU=ChatApp, O=MyCompany, L=City, ST=State, C=US"
```

**Create Client Truststore:**
```bash
# Export server certificate
keytool -exportcert \
  -alias server \
  -keystore server.jks \
  -storepass [keystore-password] \
  -file server.crt

# Create truststore
keytool -importcert \
  -alias server \
  -file server.crt \
  -keystore truststore.jks \
  -storepass [truststore-password] \
  -noprompt
```

### 2. Compile the Application
```bash
javac -d out/production/assign2 src/*.java
```

### 3. Start the Server
```bash
java -Djavax.net.ssl.keyStore=server.jks \
     -Djavax.net.ssl.keyStorePassword=[keystore-password] \
     -cp out/production/assign2 \
     Server
```

**Expected output:**
```
TLS Server listening on port 9999
Using keystore: server.jks
Initialized user_state.txt with default users
Loaded 2 users from user_state.txt
```

### 4. Connect with Client
Open a new terminal and run:
```bash
java -Djavax.net.ssl.trustStore=truststore.jks \
     -Djavax.net.ssl.trustStorePassword=[truststore-password] \
     -cp out/production/assign2 \
     Client
```
**Expected output:**
```
=== Welcome :) ===

Enter your username: 
```

## Default User Accounts

The system comes with two pre-configured accounts:

| Username | Password  | Description |
|----------|-----------|-------------|
| `admin`  | `admin123` | Administrator account |
| `guest`  | `guest123` | Guest account |

*New users can register automatically by providing new credentials during login.*

## Client Commands

| Command | Description | Example |
|---------|-------------|---------|
| `/join <room>` | Join or create a regular chat room | `/join general` |
| `/join AI:<name>` | Join/create AI room with default prompt | `/join AI:assistant` |
| `/join AI:<name>\|<prompt>` | Join/create AI room with custom prompt | `/join AI:helper\|You are a coding assistant` |
| `/leave` | Leave current room | `/leave` |
| `/rooms` | List all available rooms | `/rooms` |
| `/help` | Show command help | `/help` |
| `/quit` | Exit client and delete session | `/quit` |

## Usage Examples

### Basic Chat
```
=== Welcome :) ===

Enter your username: user
Enter your password: pass

Logged in successfully! Token saved to session_user.token

Resumed session :)

Commands:
  /join <room>              — join or create a room
  /join AI:<room>           — join or create a room with chat bot (default prompt)
  /join AI:<name>|<prompt>  — join or create a room with chat bot
  /leave                    — leave current room
  /rooms                    — list all rooms
  /quit                     — exit client
  /help                     — show this list
  
> /rooms
Available rooms:
- kitchen (2 users)

/join kitchen
-- user has joined the room: kitchen --
> hi!
user: hi!
user2: hi! how are you?
```

### AI Chat
```
> /join AI:chat_with_bot
-- user has joined the room: chat_with_bot --
> hi!
user: hi!
Bot: How can I assist you today?
> how is the weather in Porto today?
user: how is the weather in Porto today?
Bot: The weather in Porto, Portugal is currently overcast with light rain showers, with temperatures around 14C (57F).
```


### Custom AI Prompt
```
> /join AI:coder|You are a helpful coding assistant specialized in Java
-- user has joined the room: coder --
> How do I create a thread in Java?
user: How do I create a thread in Java?
Bot: To create a thread in Java, you can use the `Thread` class. Here's an example of how to do it:

```java
public class ThreadExample {
    public static void main(String[] args) {
        // Create a new Thread object
        Thread thread = new Thread(User::doSomething);

        // Start the thread
        thread.start();
    }

    private static void doSomething() {
        System.out.println("Doing something...");
    }
}

In this example, we create a `Thread` object called `thread`, and then pass a lambda expression (`User::doSomething`) to its constructor. The lambda expression is a reference to a method that will be executed when the thread is started.

```

## Development

### File Locations

- **User data**: `user_state.txt` (auto-generated)
- **Session tokens**: `session_<username>.token` (auto-generated)
- **Server certificate**: `server.jks` (needs to be created)
- **Client truststore**: `truststore.jks` (needs to be created)
- **Compiled classes**: `out/production/assign2/` (generated during compilation)



### Project Structure
```
├── src/
│   ├── Server.java            # Main server application
│   ├── Client.java            # Client application
│   ├── ChatRoom.java          # Chat room management
│   ├── UserManager.java       # User authentication & persistence
│   └── TokenManager.java      # Session token handling
├── server.jks                 # Server TLS certificate
├── truststore.jks             # Client truststore
├── session_<username>.token   # Client truststore
└── user_state.txt             # User data
```

### Key Features Implementation

- **Virtual Threads**: Lightweight concurrency for handling multiple clients
- **Thread-safe operations**: Uses `ReadWriteLock` for concurrent access
- **Token-based Authentication**: Secure session management with expiration
- **State persistence**: File-based user and session management
- **Automatic Recovery**: Comprehensive fault tolerance mechanisms


---
