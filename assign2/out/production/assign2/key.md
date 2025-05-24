# 1. Generate server keystore (run this in your project directory)
keytool -genkeypair -alias server -keyalg RSA -keysize 2048 \
-validity 365 -keystore server.jks -storepass serverpass \
-keypass serverpass -dname "CN=localhost"

# 2. Export server certificate
keytool -exportcert -alias server -keystore server.jks \
-storepass serverpass -file server.crt

# 3. Create truststore for client
keytool -importcert -alias server -file server.crt \
-keystore truststore.jks -storepass trustpass -noprompt

# 4. Clean up certificate file (optional)
rm server.crt