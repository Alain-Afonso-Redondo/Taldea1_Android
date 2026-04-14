# OSIS Login App

Aplicación Android de login básico utilizando Kotlin, Jetpack Compose y Room Database.

## Características

- ✅ Login con email y contraseña
- ✅ Base de datos SQLite con Room
- ✅ Contraseñas hasheadas (SHA-256)
- ✅ Persistencia de sesión con DataStore
- ✅ Pantalla Home con información de usuario
- ✅ Logout y cierre de sesión
- ✅ Interfaz moderna con Jetpack Compose
- ✅ Material Design 3

## Credenciales de prueba

**Usuario 1:**
- Email: `admin@example.com`
- Contraseña: `123456`

**Usuario 2:**
- Email: `user@example.com`
- Contraseña: `password`

## Requisitos

- Android Studio Hedgehog (2023.1.1) o superior
- Android SDK 34
- Gradle 8.2.0
- Kotlin 1.9.20

## Instalación

1. Abre el proyecto en Android Studio
2. Sincroniza los archivos Gradle
3. Ejecuta la aplicación en un emulador o dispositivo con Android 8+ (API 26+)

## Estructura del proyecto

```
OSIS_Server/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/osislogin/
│   │   │   ├── MainActivity.kt          # Actividad principal
│   │   │   ├── Navigation.kt            # Navegación con Compose
│   │   │   ├── data/
│   │   │   │   ├── User.kt              # Entidad de usuario
│   │   │   │   ├── UserDao.kt           # Data Access Object
│   │   │   │   └── AppDatabase.kt       # Base de datos Room
│   │   │   ├── ui/
│   │   │   │   ├── LoginScreen.kt       # Pantalla de login
│   │   │   │   ├── HomeScreen.kt        # Pantalla de inicio
│   │   │   │   ├── LoginViewModel.kt    # ViewModel del login
│   │   │   │   └── HomeViewModel.kt     # ViewModel del home
│   │   │   └── util/
│   │   │       ├── HashingUtil.kt       # Funciones de hashing
│   │   │       └── SessionManager.kt    # Gestión de sesión
│   │   ├── AndroidManifest.xml
│   │   └── res/
│   │       └── values/
│   │           ├── strings.xml
│   │           └── styles.xml
│   ├── build.gradle.kts
│   └── proguard-rules.pro
├── build.gradle.kts
├── settings.gradle.kts
└── README.md
```

## Dependencias principales

- **Jetpack Compose**: Interfaz de usuario moderna
- **Room Database**: Base de datos local persistente
- **DataStore**: Almacenamiento seguro de preferencias
- **Lifecycle**: Gestión del ciclo de vida
- **Navigation Compose**: Navegación entre pantallas

## Flujo de la aplicación

1. **Splash/Check Sesión**: Al iniciar, verifica si hay sesión activa
2. **Login**: Si no hay sesión, muestra la pantalla de login
3. **Autenticación**: Valida credenciales contra la base de datos
4. **Home**: Después del login, muestra la pantalla de inicio con datos del usuario
5. **Logout**: El usuario puede cerrar sesión volviendo al login

## Cómo añadir más usuarios

Para agregar más usuarios a la base de datos, edita el archivo `AppDatabase.kt` en la función `onCreate()`:

```kotlin
db.execSQL(
    "INSERT INTO users (email, password, fullName) VALUES ('nuevo@example.com', '${hashUtil.hashPassword("tu_contraseña")}', 'Tu Nombre')"
)
```

## Mejoras futuras

- [ ] Integración con API REST
- [ ] Recuperación de contraseña
- [ ] Autenticación biométrica
- [ ] Perfiles de usuario más complejos
- [ ] Notificaciones push
- [ ] Sincronización con servidor

## Licencia

Este proyecto es de código abierto.

---

**Creado para OSIS Learning Management System**
