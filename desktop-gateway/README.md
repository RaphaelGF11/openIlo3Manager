# ilo3-gateway

Passerelle locale qui rend l'interface web d'un iLO 3 accessible depuis un navigateur moderne.

```sh
cargo build --release
./target/release/ilo3-gateway            # écoute sur 127.0.0.1:8080
```

Puis, dans le navigateur :

```
http://127.0.0.1:8080/192.168.1.230/443/
```

## État : ne se connecte pas encore

Le proxy HTTP est complet et fonctionne ; **la couche TLS est bloquée**, et le blocage n'est pas
dans ce code.

Un iLO 3 n'offre que TLS 1.0/1.1 avec **3DES ou RC4**. Or ces chiffrements ont été retirés à la
compilation de toutes les branches OpenSSL 3.x — vérifié sur les deux :

| Source | Version | `DES-CBC3-SHA` |
|---|---|---|
| OpenSSL du système (Ubuntu) | 3.0.13 | absent |
| OpenSSL embarquée (`features = ["vendored"]`) | 3.6.3 | absent |

La négociation échoue donc avant même que l'appareil ne soit consulté. Le message d'erreur
(`handshake failure`, alerte 40) désigne à tort le distant.

### Pistes

1. **Compiler contre OpenSSL 1.1.1**, qui contient encore 3DES, via `OPENSSL_DIR`. C'est la voie
   la plus directe. `openssl-src` ne permet pas de choisir la branche 1.1.1 depuis le `Cargo.toml` :
   il faut construire OpenSSL 1.1.1 séparément et pointer la variable dessus.
2. **Une implémentation TLS qui possède encore ces primitives.** C'est le choix fait par
   l'application Android de ce dépôt : elle utilise Bouncy Castle précisément parce que la pile du
   système refusait la même chose.

Ne pas perdre de temps sur la liste de chiffrements ni sur `SECLEVEL=0` : les deux sont déjà
corrects, le problème est l'absence de l'algorithme dans la bibliothèque.

## Le piège des query strings

Le serveur web d'un iLO 3 **ne sait pas analyser un corps de requête si la ligne de requête porte
une query string** — n'importe laquelle, même un `?` seul. Il répond
`Malformed object, expected '{' at start of object` et le réglage échoue silencieusement. Vérifié
sur un appareil réel avec `?`, `?null`, `?_=123` et `?x=1`.

`build_request` supprime donc la query dès qu'il y a un corps. L'interface ne s'en sert que pour
contourner les caches, la perte est sans effet. **Ne pas « corriger » ce comportement.**

## Chemins absolus

Les pages de l'iLO référencent des chemins absolus (`/json/login_session`, `/js/…`), qui
sortiraient du préfixe de routage. La première requête préfixée dépose donc un cookie retenant la
cible, et un repli sur l'en-tête `Referer` couvre le reste — plutôt que de réécrire le HTML et le
JavaScript, ce qui casserait sur les URL construites dynamiquement.
