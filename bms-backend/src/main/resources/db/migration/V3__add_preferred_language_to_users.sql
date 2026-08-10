-- Add preferred_language column to users (the logged-in user's preferred UI language)
ALTER TABLE users ADD COLUMN preferred_language VARCHAR(10) NOT NULL DEFAULT 'en';
