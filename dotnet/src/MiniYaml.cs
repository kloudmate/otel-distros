using System;
using System.Collections.Generic;
using System.Text;

namespace KloudMate.OpenTelemetry
{
    /// <summary>
    /// Reads one nested mapping (such as distribution.kloudmate) out of a YAML config file, without a YAML library:
    /// the plugin may not ship dependencies of its own. Handles what the KloudMate Agent writes and what people write
    /// by hand for this block: block mappings by indentation, flow mappings on one line ({ a: b, c: d }), quoted and
    /// plain scalars, comments, and environment substitution (${VAR}, ${env:VAR}, ${VAR:-default}). Lists and
    /// anything else are skipped. Never throws.
    /// </summary>
    internal static class MiniYaml
    {
        /// <summary>The mapping at the given key path, or null when the file has none.</summary>
        internal static Dictionary<string, object>? Block(string? text, params string[] path)
        {
            try
            {
                if (text == null || path.Length == 0) return null;
                var lines = Lines(text);
                var i = 0;
                Dictionary<string, object>? current = Parse(lines, ref i, 0);
                foreach (var key in path)
                {
                    if (current == null || !current.TryGetValue(key, out var next) || next is not Dictionary<string, object> map) return null;
                    current = map;
                }
                return current;
            }
            catch
            {
                return null;
            }
        }

        private readonly struct Line
        {
            internal Line(int indent, string text)
            {
                Indent = indent;
                Text = text;
            }

            internal int Indent { get; }
            internal string Text { get; }
        }

        private static List<Line> Lines(string text)
        {
            var list = new List<Line>();
            foreach (var raw in text.Replace("\r\n", "\n").Split('\n'))
            {
                var t = StripComment(raw).TrimEnd();
                if (t.Trim().Length == 0 || t.Trim() == "---") continue;
                var indent = 0;
                while (indent < t.Length && t[indent] == ' ') indent++;
                list.Add(new Line(indent, t.Substring(indent)));
            }
            return list;
        }

        /// <summary>A block mapping whose keys sit at exactly this indent.</summary>
        private static Dictionary<string, object> Parse(List<Line> lines, ref int i, int indent)
        {
            var map = new Dictionary<string, object>(StringComparer.Ordinal);
            while (i < lines.Count && lines[i].Indent >= indent)
            {
                var line = lines[i];
                if (line.Indent > indent || line.Text.StartsWith("-", StringComparison.Ordinal))
                {
                    i++; // a list item or deeper content of a skipped value
                    continue;
                }
                var colon = KeyColon(line.Text);
                if (colon < 0)
                {
                    i++;
                    continue;
                }
                var key = Unquote(line.Text.Substring(0, colon).Trim());
                var rest = line.Text.Substring(colon + 1).Trim();
                i++;
                if (rest.Length == 0)
                {
                    if (i < lines.Count && lines[i].Indent > indent && !lines[i].Text.StartsWith("-", StringComparison.Ordinal))
                    {
                        map[key] = Parse(lines, ref i, lines[i].Indent);
                    }
                    else
                    {
                        while (i < lines.Count && lines[i].Indent > indent) i++; // a list or nothing
                    }
                }
                else if (rest.StartsWith("{", StringComparison.Ordinal) && rest.EndsWith("}", StringComparison.Ordinal))
                {
                    map[key] = Flow(rest.Substring(1, rest.Length - 2));
                }
                else if (!rest.StartsWith("[", StringComparison.Ordinal))
                {
                    map[key] = Scalar(rest);
                }
            }
            return map;
        }

        private static Dictionary<string, object> Flow(string body)
        {
            var map = new Dictionary<string, object>(StringComparer.Ordinal);
            foreach (var part in body.Split(','))
            {
                var colon = KeyColon(part);
                if (colon < 0) continue;
                map[Unquote(part.Substring(0, colon).Trim())] = Scalar(part.Substring(colon + 1).Trim());
            }
            return map;
        }

        /// <summary>The colon that ends a key: followed by a space or the end, outside quotes.</summary>
        private static int KeyColon(string s)
        {
            var quote = '\0';
            for (var i = 0; i < s.Length; i++)
            {
                var c = s[i];
                if (quote != '\0')
                {
                    if (c == quote) quote = '\0';
                    continue;
                }
                if (c == '"' || c == '\'') quote = c;
                else if (c == ':' && (i + 1 == s.Length || s[i + 1] == ' ')) return i;
            }
            return -1;
        }

        private static string StripComment(string s)
        {
            var quote = '\0';
            for (var i = 0; i < s.Length; i++)
            {
                var c = s[i];
                if (quote != '\0')
                {
                    if (c == quote) quote = '\0';
                    continue;
                }
                if (c == '"' || c == '\'') quote = c;
                else if (c == '#' && (i == 0 || s[i - 1] == ' ' || s[i - 1] == '\t')) return s.Substring(0, i);
            }
            return s;
        }

        private static string Scalar(string s) => Substitute(Unquote(s.Trim()));

        private static string Unquote(string s)
        {
            if (s.Length >= 2 && ((s[0] == '"' && s[s.Length - 1] == '"') || (s[0] == '\'' && s[s.Length - 1] == '\'')))
            {
                return s.Substring(1, s.Length - 2);
            }
            return s;
        }

        /// <summary>${VAR}, ${env:VAR} and ${VAR:-default}, from the process environment.</summary>
        internal static string Substitute(string s)
        {
            if (s.IndexOf("${", StringComparison.Ordinal) < 0) return s;
            var b = new StringBuilder();
            var i = 0;
            while (i < s.Length)
            {
                var start = s.IndexOf("${", i, StringComparison.Ordinal);
                var end = start < 0 ? -1 : s.IndexOf('}', start);
                if (start < 0 || end < 0)
                {
                    b.Append(s, i, s.Length - i);
                    break;
                }
                b.Append(s, i, start - i);
                var expr = s.Substring(start + 2, end - start - 2);
                if (expr.StartsWith("env:", StringComparison.Ordinal)) expr = expr.Substring(4);
                var dflt = string.Empty;
                var sep = expr.IndexOf(":-", StringComparison.Ordinal);
                if (sep >= 0)
                {
                    dflt = expr.Substring(sep + 2);
                    expr = expr.Substring(0, sep);
                }
                b.Append(Env.Get(expr) ?? dflt);
                i = end + 1;
            }
            return b.ToString();
        }
    }
}
