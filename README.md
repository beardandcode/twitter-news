This pulls in data from twitter about links shared and the conversation around them, munges it a bit and then serves it up.

This project is a **prototype**, the quality of the code is not quite as high as might be desired.

## Dependencies

  - [Leiningen 2+](http://leiningen.org)
  - PhantomJS 1.9.8 - Sadly Selenium doesn't yet support PhantomJS 2+, [PhantomJS 1.9.8 can be found here](https://bitbucket.org/ariya/phantomjs/downloads)
  - [sassc](http://github.com/sass/sassc) - Can be found in homebrew for OSX

## Credentials

We require you to pass in Twitter credentials as environment variables

```
TWITTER_CONSUMER_KEY="<your key>"
TWITTER_CONSUMER_SECRET="<your secret>"
TWITTER_TOKEN="<your token>"
TWITTER_SECRET="<your secret>"
```

These can be find these by [going to Application Manangement](https://apps.twitter.com).

## Try

[![Deploy](https://www.herokucdn.com/deploy/button.png)](https://heroku.com/deploy)

Or, find your way to the directory where you checked out this project and execute the following:

```
$ lein repl

user=> (go)  ;; starts the example webapp on a random port
...
...
... c.beardandcode.components.web-server - Started web server on http://127.0.0.1:8080

user=> (open!)  ;; only works on OSX

```

If you are not on OS X, open the url found in your logs it will likely be different to the one above.
