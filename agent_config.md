curl --location 'https://quasarmarket.coforge.com/qag/llmrouter-api/v2/chat/completions' \
--header 'Content-Type: application/json' \
--header 'X-API-KEY: b9620fa1-4f98-4f04-9124-3f7df8081dda' \
--data '{
  "model": "kimi-k2-thinking",
  "messages": [
    {
      "role": "user",
      "content": "You are helpful AI. Reply to: Hello Kimi test"
    }
  ],
  "temperature": 0.8,
  "top_p": 0.9,
  "max_tokens": 1000
}'
  curl --location 'https://quasarmarket.coforge.com/qag/llmrouter-api/v2/chat/completions' \
--header 'Content-Type: application/json' \
--header 'X-API-KEY: 44ca620e-5a7f-4bec-9cee-f2a6073d0712' \
--data '{
  "model": "claude-sonnet-3-5",
  "messages": [
    {
      "role": "user",
      "content": "You are helpful AI. Reply to: Hello Claude test"
    }
  ],
  "temperature": 0.8,
  "top_p": 0.9,
  "max_tokens": 1000
}'
  curl --location 'https://quasarmarket.coforge.com/qag/llmrouter-api/v2/chat/completions' \
--header 'Content-Type: application/json' \
--header 'X-API-KEY: 2f9393c2-c0e8-41e3-8c72-bc6a4e2bd31a' \
--data '{
  "model": "gemini-2-5-flash",
  "messages": [
    {
      "role": "user",
      "content": "You are helpful AI. Reply to: Hello Gemini test"
    }
  ],
  "temperature": 0.8,
  "top_p": 0.9,
  "max_tokens": 1000
}'
  curl --location 'https://quasarmarket.coforge.com/qag/llmrouter-api/v2/chat/completions' \
--header 'Content-Type: application/json' \
--header 'X-API-KEY: 823691f4-bec2-45fb-83d1-b8a786953b03' \
--data '{
  "model": "gpt-5-2-chat",
  "messages": [
    {
      "role": "user",
      "content": "You are helpful AI. Reply to: Hello GPT test"
    }
  ],
  "temperature": 0.8,
  "top_p": 0.9,
  "max_tokens": 1000
}'
