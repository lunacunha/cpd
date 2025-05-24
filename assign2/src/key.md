# TLS Setup and Deployment Guide

This guide explains how to generate the necessary TLS certificates and deploy the chat application with proper security.

## Overview

The application uses **server-only authentication** with TLS, which means:
- The **server** needs a **keystore** containing its private key and certificate
- The **client** needs a **truststore** containing the server's public certificate
- Both stores are password-protected for security

## Step 1: Generate Server Keystore

First, create the server's keystore with a self-signed certificate:

```bash
keytool -genkeypair -alias server -keyalg RSA -keysize 2048 \
        -validity 365 -keystore server.jks -storepass changeit \
        -keypass changeit -dname "CN=localhost"
```

**Parameters explained:**
- `-alias server`: Name for the key entry
- `-keyalg RSA`: Use RSA algorithm
- `-keysize 2048`: 2048-bit key size
- `-validity 365`: Certificate valid for 365 days
- `-keystore server.jks`: Output keystore filename
- `-storepass changeit`: Password to protect the keystore
- `-keypass changeit`: Password to protect the private key
- `-dname "CN=localhost"`: Distinguished name (Common Name = localhost)

## Step 2: Export Server Certificate

Extract the server's public certificate:

```bash
keytool -exportcert -alias server -keystore server.jks \
        -storepass changeit -file server.crt
```

This creates `server.crt` containing the server's public certificate.

## Step 3: Create Client Truststore

Import the server certificate into the client's truststore:

```bash
keytool -importcert -alias server -file server.crt \
        -keystore truststore.jks -storepass changeit
```

When prompted "Trust this certificate? [no]:", type `yes`.

## Step 4: Verify Certificates (Optional)

List contents of keystore:
```bash
keytool -list -keystore server.jks -storepass changeit
```

List contents of truststore:
```bash
keytool -list -keystore truststore.jks -storepass changeit
```

## Step 5: Deploy the Application

### Running the Server

```bash
java -Djavax.net.ssl.keyStore=server.jks \
     -Djavax.net.ssl.keyStorePassword=changeit \
     -Djavax.net.ssl.keyStoreType=JKS \
     Server
```

### Running the Client

```bash
java -Djavax.net.ssl.trustStore=truststore.jks \
     -Djavax.net.ssl.trustStorePassword=changeit \
     -Djavax.net.ssl.trustStoreType=JKS \
     Client
```

## Security Considerations

### Password Management
- **Development**: Use simple passwords like `changeit`
- **Production**: Use strong, unique passwords
- **Deployment**: Pass passwords via environment variables or secure configuration files

### Certificate Validity
- The example uses 365 days validity
- For demonstrations, this is sufficient
- For production, consider longer validity periods
- Monitor expiration dates and renew before expiry

### Self-Signed vs CA-Signed Certificates
- **Self-signed** (used here): Fine for private/internal use
- **CA-signed**: Required for public-facing applications
- **Production**: Use certificates from trusted Certificate Authorities

## Alternative Deployment Methods

### Using Environment Variables

Set passwords via environment:
```bash
export KEYSTORE_PASSWORD=your_secure_password
export TRUSTSTORE_PASSWORD=your_secure_password

java -Djavax.net.ssl.keyStore=server.jks \
     -Djavax.net.ssl.keyStorePassword=${KEYSTORE_PASSWORD} \
     Server
```

### Using Different Store Types

For PKCS12 format (more modern):
```bash
# Generate PKCS12 keystore
keytool -genkeypair -alias server -keyalg RSA -keysize 2048 \
        -validity 365 -storetype PKCS12 -keystore server.p12 \
        -storepass changeit -keypass changeit -dname "CN=localhost"

# Run with PKCS12
java -Djavax.net.ssl.keyStore=server.p12 \
     -Djavax.net.ssl.keyStorePassword=changeit \
     -Djavax.net.ssl.keyStoreType=PKCS12 \
     Server
```

## Troubleshooting

### Common Errors

1. **"Keystore was tampered with, or password was incorrect"**
    - Check that the store password is correct
    - Verify the keystore file isn't corrupted

2. **"sun.security.provider.certpath.SunCertPathBuilderException"**
    - Server certificate not in client's truststore
    - Re-import the certificate into truststore

3. **"No trusted certificate found"**
    - Client's truststore doesn't contain server certificate
    - Verify certificate was properly imported

4. **"Connection refused"**
    - Server not running or wrong port
    - Check server startup messages

### Verification Commands

Test certificate chain:
```bash
openssl x509 -in server.crt -text -noout
```

Test TLS connection:
```bash
openssl s_client -connect localhost:9999 -verify_return_error
```

## File Summary

After setup, you should have:
- `server.jks` - Server's keystore (private key + certificate)
- `truststore.jks` - Client's truststore (server's public certificate)
- `server.crt` - Server certificate (can be deleted after truststore creation)

Keep the keystore secure and never share private keys. The truststore can be distributed to all clients that need to connect to your server.