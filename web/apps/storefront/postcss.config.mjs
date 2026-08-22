/**
 * Tailwind CSS v4 is a PostCSS plugin and takes its configuration from CSS (`@theme` in
 * globals.css) rather than from a JavaScript config file — there is deliberately no
 * `tailwind.config.js` in this project.
 */
const config = {
  plugins: {
    '@tailwindcss/postcss': {},
  },
};

export default config;
