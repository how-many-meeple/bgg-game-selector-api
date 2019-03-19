# bgg-game-selector-api

A JSON rest service for interacting with Board game geek (including geeklists).  The service wraps retrying BGG until data is returned (or timeouts occur) and filters out unwanted games based on headers passed.

## APIs

The API suite consists of the following endpoints:

### Retrieve player collection
`/collection/<username>`

If the user is not found the service will return a 404.

### Retrieve geek list
`/geeklist/<geek_list>`

If the geeklist is not found or empty the service will return a 404.

### Search BGG games list
`/search/<string>`

## Filter and field Headers

The collection and geek list APIs can be filtered and the response reduced using the following headers.

| Header Name | Value Type | Description |
|----|----|----|
| Bgg-Filter-Player-Count | Int | Number of players game must support |
| Bgg-Filter-Min-Duration | Int | Game must last at least this long |
| Bgg-Filter-Max-Duration | Int | Game must not last longer than this |
| Bgg-Field-Whitelist | String | Comma separated list of fields to include in the game response list |
