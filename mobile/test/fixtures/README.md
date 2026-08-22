# Fixtures

Captured from a running API against the seeded development catalog, not written by hand.

That is the point of them. Every field name here is one the server actually sent, so a model that
reads `price` where the server says `cost` fails at test time rather than rendering 0.00 EGP on a
customer's phone. Three such mismatches were in this codebase before these existed.

Regenerate by running the API with `make dev`, seeding a customer and an order, and saving the
responses to the matching filenames.
