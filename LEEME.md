# COBRAPASS - COBRAMC

Proyecto fuente para Paper 1.21.4, Java 21.

Incluye:
- 54 niveles Gratis y Premium
- 100B de dinero Gratis y 200B Premium adicionales
- 55 misiones (15 diarias, 40 semanales)
- Menus propios sin DeluxeMenus ni Skript
- Guardado SQLite por temporada
- CrazyCrates, EssentialsX, PlaceholderAPI
- Comando /pase y alias /bp

## Compilar

GitHub Actions:
1. Crea un repositorio privado nuevo en GitHub.
2. Sube el CONTENIDO del ZIP a la raiz del repositorio.
3. Ve a Actions > Compilar CobraPass > Run workflow.
4. Si la compilacion termina correctamente, descarga el artefacto CobraPass-JAR.
5. Extrae CobraPass-1.0.0.jar del artefacto.

Compilacion local alternativa:
mvn clean package

## Instalar
1. Haz copia de seguridad del servidor.
2. Copia el JAR compilado a plugins/.
3. Reinicia Paper 1.21.4.
4. Edita plugins/CobraPass/rewards.yml, quests.yml, config.yml si deseas.
5. Reinicia o usa /cpassadmin reload para configs.

## Admin
/cpassadmin premium ToraGGx on
/cpassadmin addxp ToraGGx 200
/cpassadmin resetclaim ToraGGx gratis 1
/cpassadmin season next
/cpassadmin reload

## Tebex (despues de confirmar pago)
cpassadmin premium {username} on

## TAB / Scoreboard
%cobrapass_level%
%cobrapass_xp%
%cobrapass_xp_required%
%cobrapass_type%
%cobrapass_coins%
%cobrapass_days_left%

## ADVERTENCIAS
- ESTE ES CODIGO FUENTE, no un .jar ya compilado.
- No se ha probado dentro de HolyHosting.
- Verifica IDs exactos de cajas CrazyCrates: Comun, Raro, Legendario, Dinero, Spawners, Divino.
- Las misiones de interacciones/comercio registran eventos; no demuestran economia real.
- Las recompensas quedan marcadas reclamadas ANTES de la entrega externa para evitar duplicados.
- Si un comando de EssentialsX/CrazyCrates falla, revisa la consola ANTES de resetclaim.
- El Premium es por temporada; no se conserva entre temporadas.
- Los comandos de admin afectan a jugadores cuyo UUID coincida con el nombre en el servidor.
