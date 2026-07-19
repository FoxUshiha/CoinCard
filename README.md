# CoinCard
Minecraft plugin for using coin cards to buy and sell coins for vault cash.

Depends on Vault and an econnomy plugin.

- Coin Project: https://github.com/FoxUshiha/DC-Coin-Bot
- Discord Bot: https://discord.com/oauth2/authorize?client_id=1391067775077978214
- Tutorial: https://youtu.be/QY1CqjTONwE
- Coin API and Site: https://bank.foxsrv.net/
(You can host your own)

# Made for Minecraft 1.20+ Servers.

Placeholder: %coin_user%

Depends: https://www.spigotmc.org/resources/placeholderapi.6245/

Commands:

- coin or coincard (main)
- buy (buys vault cash for coins)
- sell (buys coins for vault cash)
- card (setups your coin card to use)
- server pay (pays coins using server card to users)
- reload (reloads the server configs)
- balance
- pay
- baltop

It has the card payment and user coins management exported to API.
It means that some other coin plugins now depends on this plugin.

Config:
  ```
   # Coin economy config
Main: false     # If true, this plugin will be the main Server economy manager and will control other vault based economy plugins.

Server: "5e8127e5-646b-36d6-9ff7-ace1050597d8"     # Server Vault Account UUID
Card: "e1301fadfc35"                               # Server Card ID
Buy: 1.00                                       # Vault received for each 1.00000000 coin (coins->vault)
Sell: 0.00000100                                   # Coins received for each 1.00 vault (vault->coins)
API: "https://bank.foxsrv.net"  # The API URL - You can host your own using https://github.com/FoxUshiha/DC-Coin-Bot
# General
Decimals: 2                  # Coin Decimals between 0 and 8, constrols things like 8= 0.00000001 and 0= 1 (changes the number lenght)
QueueIntervalTicks: 20       # process 1 task / 20 ticks (~1s)
PerUserCooldownMs: 1100      # cooldown de /coin pay, /coin buy, /coin sell
TimeoutMs: 60000             # timeout HTTP `

  ```
