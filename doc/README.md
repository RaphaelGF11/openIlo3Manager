# Documentation — iLO3 Manager

Documentation complète de l'application. Pour une prise en main rapide, commencez par le [tutoriel](../TUTORIAL.md).

## Utilisation

| Document | Contenu |
|---|---|
| [Installation](installation.md) | Choisir le bon APK, installer, mettre à jour |
| [Configuration d'un hôte](configuration-hote.md) | Les quatre onglets de la fiche : Général, Authentification, IPMI, VPN |
| [Fonctionnalités](fonctionnalites.md) | Alimentation, console série, console CLI, matériel, passerelle web |
| [Tunnels VPN](vpn.md) | WireGuard intégré et rebond SSH : ce que chacun permet |
| [Sauvegarde Google Drive](sauvegarde.md) | Chiffrement, restauration, et le projet Google Cloud requis |

## Comprendre et diagnostiquer

| Document | Contenu |
|---|---|
| [Sécurité](securite.md) | Où vivent vos identifiants, ce qui sort de l'appareil, les risques assumés |
| [Dépannage](depannage.md) | Messages d'erreur et leurs causes réelles |
| [Architecture](architecture.md) | Choix techniques et particularités du firmware iLO3, pour contribuer |

## En bref

L'application administre un serveur HP ProLiant via son processeur de gestion iLO3, par trois voies complémentaires :

- **SSH** vers la CLI de l'iLO — universelle, mais lente sur ce matériel ;
- **IPMI** sur le réseau — bien plus rapide, mais à activer sur l'iLO et limitée aux capteurs ;
- **HTTPS** vers l'interface web d'origine, via une passerelle locale qui reconstitue le TLS ancien que les navigateurs modernes refusent.

Chaque voie a ses limites, documentées à l'endroit où elles comptent plutôt que passées sous silence.
